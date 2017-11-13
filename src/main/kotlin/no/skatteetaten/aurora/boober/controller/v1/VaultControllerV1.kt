package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.facade.VaultFacade
import no.skatteetaten.aurora.boober.model.AuroraSecretVault
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

data class AuroraSecretVaultPayload(val vault: AuroraSecretVault, val validateVersions: Boolean = true)
data class UpdateSecretFilePayload(val contents: String, val validateVersions: Boolean = true, val version: String = "")

@RestController
@RequestMapping("/v1/vault/{affiliation}")
class VaultControllerV1(val facade: VaultFacade) {

    @GetMapping()
    fun listVaults(@PathVariable affiliation: String): Response {
        return Response(items = facade.listAllVaultsWithUserAccess(affiliation))
    }

    @PutMapping()
    fun save(@PathVariable affiliation: String,
             @RequestBody @Valid vaultPayload: AuroraSecretVaultPayload): Response {

        return Response(items = listOf(facade.save(affiliation, vaultPayload.vault, vaultPayload.validateVersions)))
    }

    @GetMapping("/{vault}")
    fun get(@PathVariable affiliation: String, @PathVariable vault: String): Response {
        return Response(items = listOf(facade.find(affiliation, vault)))
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

        val vault = facade.updateSecretFile(affiliation, vaultName, fileName, fileContents, fileVersion, validateVersions)
        return Response(items = listOf(vault))
    }

    @DeleteMapping("/{vault}")
    fun delete(@PathVariable affiliation: String, @PathVariable vault: String): Response {
        return Response(items = listOf(facade.delete(affiliation, vault)))
    }
}


