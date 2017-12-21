package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.VaultService
import no.skatteetaten.aurora.boober.model.Vault
import org.springframework.util.AntPathMatcher
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.servlet.http.HttpServletRequest
import javax.validation.Valid

data class AuroraSecretVaultPayload(val vault: Vault, val validateVersions: Boolean = true)
data class UpdateSecretFilePayload(val contents: String, val validateVersions: Boolean = true, val version: String = "")

@RestController
@RequestMapping("/v1/vault/{vaultCollection}")
class VaultControllerV1(val vaultService: VaultService) {

    @GetMapping()
    fun listVaults(@PathVariable vaultCollection: String): Response {
        return Response(items = vaultService.findAllVaultsWithUserAccessInVaultCollection(vaultCollection))
    }

    @PutMapping()
    fun save(@PathVariable vaultCollection: String,
             @RequestBody @Valid vaultPayload: AuroraSecretVaultPayload): Response {

        return Response(items = listOf(vaultService.save(vaultCollection, vaultPayload.vault, vaultPayload.validateVersions)))
    }

    @GetMapping("/{vault}")
    fun get(@PathVariable affiliation: String, @PathVariable vault: String): Response {
        return Response(items = listOf(vaultService.findVault(affiliation, vault)))
    }

    @PutMapping("/{vault}/secret/**")
    fun updateSecretFile(@PathVariable affiliation: String,
                         @PathVariable("vault") vaultName: String,
                         request: HttpServletRequest,
                         @RequestBody payload: UpdateSecretFilePayload): Response {

        val fileVersion: String = payload.version
        val validateVersions: Boolean = payload.validateVersions
        val fileContents: String = payload.contents

        if (validateVersions && fileVersion.isEmpty()) {
            throw IllegalAccessException("Must specify AuroraConfigFileVersion header")
        }

        val path = "affiliation/$affiliation/vault/$vaultName/secret/**"
        val fileName = AntPathMatcher().extractPathWithinPattern(path, request.requestURI)

        val vault = vaultService.updateFileInVault(affiliation, vaultName, fileName, fileContents)
        return Response(items = listOf(vault))
    }

    @DeleteMapping("/{vault}")
    fun delete(@PathVariable affiliation: String, @PathVariable vault: String): Response {
        return Response(items = listOf(vaultService.delete(affiliation, vault)))
    }
}


