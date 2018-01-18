package no.skatteetaten.aurora.boober.controller.v1

import com.fasterxml.jackson.annotation.JsonIgnore
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.v1.VaultWithAccessResource.Companion.fromEncryptedFileVault
import no.skatteetaten.aurora.boober.controller.v1.VaultWithAccessResource.Companion.fromVaultWithAccess
import no.skatteetaten.aurora.boober.service.vault.EncryptedFileVault
import no.skatteetaten.aurora.boober.service.vault.VaultService
import no.skatteetaten.aurora.boober.service.vault.VaultWithAccess
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.util.AntPathMatcher
import org.springframework.util.DigestUtils
import org.springframework.web.bind.annotation.*
import java.util.*
import javax.servlet.http.HttpServletRequest
import javax.validation.Valid

object B64 {
    private val decoder = Base64.getDecoder()
    private val encoder = Base64.getEncoder()

    fun encode(value: ByteArray): String {
        return encoder.encodeToString(value)
    }

    fun decode(value: String): ByteArray {
        return try {
            decoder.decode(value)
        } catch (e: Exception) {
            throw IllegalArgumentException("Provided content does not appear to be Base64 encoded.")
        }
    }
}

data class AuroraSecretVaultPayload(val name: String, val permissions: List<String>, val secrets: Map<String, String>?) {
    val secretsDecoded: Map<String, ByteArray>?
        get() = secrets?.map { Pair(it.key, B64.decode(it.value)) }?.toMap()
}

data class VaultWithAccessResource(val name: String, val hasAccess: Boolean, val secrets: Map<String, String>?, val permissions: List<String>?) {
    companion object {
        fun fromEncryptedFileVault(it: EncryptedFileVault): VaultWithAccessResource {
            return VaultWithAccessResource(it.name, true, encodeSecrets(it.secrets), it.permissions)
        }

        fun fromVaultWithAccess(it: VaultWithAccess): VaultWithAccessResource {
            return VaultWithAccessResource(it.vaultName, it.hasAccess, encodeSecrets(it.vault?.secrets), it.vault?.permissions)
        }

        private fun encodeSecrets(secrets: Map<String, ByteArray>?): Map<String, String>? {
            return secrets?.map { Pair(it.key, B64.encode(it.value)) }?.toMap()
        }
    }
}

data class VaultFileResource(val contents: String) {

    val decodedContents: ByteArray
        @JsonIgnore
        get() = B64.decode(contents)


    companion object {
        fun fromDecodedBytes(decodedBytes: ByteArray): VaultFileResource {
            return VaultFileResource(B64.encode(decodedBytes))
        }
    }
}

@RestController
@RequestMapping("/v1/vault/{vaultCollection}")
class VaultControllerV1(val vaultService: VaultService) {

    @GetMapping()
    fun listVaults(@PathVariable vaultCollection: String): Response {

        val resources = vaultService.findAllVaultsWithUserAccessInVaultCollection(vaultCollection)
                .map(::fromVaultWithAccess)
        return Response(items = resources)
    }

    @PutMapping()
    fun save(@PathVariable vaultCollection: String,
             @RequestBody @Valid vaultPayload: AuroraSecretVaultPayload): Response {

        val vault = vaultService.import(vaultCollection, vaultPayload.name, vaultPayload.permissions, vaultPayload.secretsDecoded ?: emptyMap())
        return Response(items = listOf(vault).map(::fromEncryptedFileVault))
    }

    @GetMapping("/{vault}")
    fun getVault(@PathVariable vaultCollection: String, @PathVariable vault: String): Response {
        val resources = listOf(vaultService.findVault(vaultCollection, vault))
                .map(::fromEncryptedFileVault)
        return Response(items = resources)
    }

    @GetMapping("/{vault}/**")
    fun getVaultFile(@PathVariable vaultCollection: String, @PathVariable vault: String, request: HttpServletRequest): ResponseEntity<Response> {

        val fileName = getVaultFileNameFromRequestUri(vaultCollection, vault, request)
        val vaultFile = vaultService.findFileInVault(vaultCollection, vault, fileName)

        return createVaultFileResponse(vaultFile)
    }

    @PutMapping("/{vault}/**")
    fun updateVaultFile(@PathVariable vaultCollection: String,
                        @PathVariable("vault") vaultName: String,
                        @RequestBody payload: VaultFileResource,
                        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) ifMatchHeader: String?,
                        request: HttpServletRequest): ResponseEntity<Response> {

        val fileContents: ByteArray = payload.decodedContents
        val fileName = getVaultFileNameFromRequestUri(vaultCollection, vaultName, request)

        vaultService.createOrUpdateFileInVault(vaultCollection, vaultName, fileName, fileContents, clearQuotes(ifMatchHeader))
        return createVaultFileResponse(fileContents)
    }

    @DeleteMapping("/{vault}/**")
    fun deleteVaultFile(@PathVariable vaultCollection: String,
                        @PathVariable("vault") vaultName: String,
                        request: HttpServletRequest): Response {

        val fileName = getVaultFileNameFromRequestUri(vaultCollection, vaultName, request)
        vaultService.deleteFileInVault(vaultCollection, vaultName, fileName)?.let(::fromEncryptedFileVault)
        return Response(items = listOf())
    }

    @DeleteMapping("/{vault}")
    fun delete(@PathVariable vaultCollection: String, @PathVariable vault: String): Response {
        vaultService.deleteVault(vaultCollection, vault)
        return Response(items = listOf())
    }

    private fun getVaultFileNameFromRequestUri(vaultCollection: String, vault: String, request: HttpServletRequest): String {
        val path = "/v1/vault/$vaultCollection/$vault/**"
        val fileName = AntPathMatcher().extractPathWithinPattern(path, request.requestURI)
        return fileName
    }

    private fun createVaultFileResponse(vaultFile: ByteArray): ResponseEntity<Response> {
        val response = Response(items = listOf(VaultFileResource.fromDecodedBytes(vaultFile)))
        val eTagHex: String = DigestUtils.md5DigestAsHex(vaultFile)
        val headers = HttpHeaders().apply { eTag = "\"${eTagHex}\"" }
        return ResponseEntity(response, headers, HttpStatus.OK)
    }
}


