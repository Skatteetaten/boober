package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.v1.VaultWithAccessResource.Companion.fromEncryptedFileVault
import no.skatteetaten.aurora.boober.model.EncryptedFileVault
import no.skatteetaten.aurora.boober.service.VaultService
import org.springframework.util.AntPathMatcher
import org.springframework.web.bind.annotation.*
import javax.servlet.http.HttpServletRequest
import javax.validation.Valid

data class AuroraSecretVaultPayload(val name: String, val permissions: List<String>, val secrets: Map<String, String>?)

data class VaultWithAccessResource(val name: String, val hasAccess: Boolean, val secrets: Map<String, String>?, val permissions: List<String>?) {
    companion object {
        fun fromEncryptedFileVault(it: EncryptedFileVault)
                = VaultWithAccessResource(it.name, true, it.secrets, it.permissions)
    }
}

data class VaultFileResource(val contents: String)

@RestController
@RequestMapping("/v1/vault/{vaultCollection}")
class VaultControllerV1(val vaultService: VaultService) {

    @GetMapping()
    fun listVaults(@PathVariable vaultCollection: String): Response {

        val resources = vaultService.findAllVaultsWithUserAccessInVaultCollection(vaultCollection)
                .map { VaultWithAccessResource(it.vaultName, it.hasAccess, it.vault?.secrets, it.vault?.permissions) }
        return Response(items = resources)
    }

    @PutMapping()
    fun save(@PathVariable vaultCollection: String,
             @RequestBody @Valid vaultPayload: AuroraSecretVaultPayload): Response {

        val vault = vaultService.import(vaultCollection, vaultPayload.name, vaultPayload.permissions, vaultPayload.secrets ?: emptyMap())
        return Response(items = listOf(vault).map(::fromEncryptedFileVault))
    }

    @GetMapping("/{vault}")
    fun getVault(@PathVariable vaultCollection: String, @PathVariable vault: String): Response {
        val resources = listOf(vaultService.findVault(vaultCollection, vault))
                .map(::fromEncryptedFileVault)
        return Response(items = resources)
    }

    @GetMapping("/{vault}/**")
    fun getVaultFile(@PathVariable vaultCollection: String, @PathVariable vault: String, request: HttpServletRequest): Response {

        val fileName = getVaultFileNameFromRequestUri(vaultCollection, vault, request)
        val vaultFile = vaultService.findFileInVault(vaultCollection, vault, fileName)

        return Response(items = listOf(VaultFileResource(vaultFile)))
    }

    @PutMapping("/{vault}/**")
    fun updateVaultFile(@PathVariable vaultCollection: String,
                        @PathVariable("vault") vaultName: String,
                        request: HttpServletRequest,
                        @RequestBody payload: VaultFileResource): Response {

        val fileContents: String = payload.contents
        val fileName = getVaultFileNameFromRequestUri(vaultCollection, vaultName, request)

        val resources = listOf(vaultService.createOrUpdateFileInVault(vaultCollection, vaultName, fileName, fileContents))
                .map(::fromEncryptedFileVault)
        return Response(items = listOf(resources))
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
}


