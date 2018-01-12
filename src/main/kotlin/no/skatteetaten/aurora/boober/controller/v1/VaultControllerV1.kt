package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.model.EncryptedFileVault
import no.skatteetaten.aurora.boober.service.VaultService
import org.springframework.util.AntPathMatcher
import org.springframework.web.bind.annotation.*
import javax.servlet.http.HttpServletRequest
import javax.validation.Valid

data class AuroraSecretVaultPayload(val vault: EncryptedFileVault, val validateVersions: Boolean = true)

data class VaultWithAccessResource(val name: String, val hasAccess: Boolean, val secrets: Map<String, String>?)
data class VaultFileResource(val contents: String)

@RestController
@RequestMapping("/v1/vault/{vaultCollection}")
class VaultControllerV1(val vaultService: VaultService) {

    @GetMapping()
    fun listVaults(@PathVariable vaultCollection: String): Response {

        val resources = vaultService.findAllVaultsWithUserAccessInVaultCollection(vaultCollection)
                .map { VaultWithAccessResource(it.vaultName, it.hasAccess, it.vault?.secrets) }
        return Response(items = resources)
    }

    @PutMapping()
    fun save(@PathVariable vaultCollection: String,
             @RequestBody @Valid vaultPayload: AuroraSecretVaultPayload): Response {

        return Response(items = listOf(vaultService.save(vaultCollection, vaultPayload.vault, vaultPayload.validateVersions)))
    }

    @GetMapping("/{vault}")
    fun getVault(@PathVariable vaultCollection: String, @PathVariable vault: String): Response {
        val resources = listOf(vaultService.findVault(vaultCollection, vault))
                .map { VaultWithAccessResource(it.name, true, it.secrets) }
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
                .map { VaultWithAccessResource(it.name, true, it.secrets) }
        return Response(items = listOf(resources))
    }

    @DeleteMapping("/{vault}/**")
    fun deleteVaultFile(@PathVariable vaultCollection: String,
                        @PathVariable("vault") vaultName: String,
                        request: HttpServletRequest): Response {

        val fileName = getVaultFileNameFromRequestUri(vaultCollection, vaultName, request)

        val resource = vaultService.deleteFileInVault(vaultCollection, vaultName, fileName)
                ?.let { VaultWithAccessResource(it.name, true, it.secrets) }
        return Response(items = if (resource != null) listOf(resource) else emptyList())
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


