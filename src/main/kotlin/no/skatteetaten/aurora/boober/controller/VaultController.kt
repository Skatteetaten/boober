package no.skatteetaten.aurora.boober.controller

import io.micrometer.core.annotation.Timed
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.facade.VaultFacade
import no.skatteetaten.aurora.boober.model.AuroraSecretVault
import org.springframework.util.AntPathMatcher
import org.springframework.web.bind.annotation.*
import javax.servlet.http.HttpServletRequest
import javax.validation.Valid

data class AuroraSecretVaultPayload(val vault: AuroraSecretVault, val validateVersions: Boolean = true)

@RestController
@RequestMapping("/affiliation/{affiliation}/vault")
class VaultController(val facade: VaultFacade) {


    @Timed
    @GetMapping()
    fun listVaults(@PathVariable affiliation: String): Response {
        return Response(items = facade.listAllVaultsWithUserAccess(affiliation))
    }

    @Timed
    @PutMapping()
    fun save(@PathVariable affiliation: String,
             @RequestBody @Valid vaultPayload: AuroraSecretVaultPayload): Response {

        return Response(items = listOf(facade.save(affiliation, vaultPayload.vault, vaultPayload.validateVersions)))
    }

    @Timed
    @GetMapping("/{vault}")
    fun get(@PathVariable affiliation: String, @PathVariable vault: String): Response {
        return Response(items = listOf(facade.find(affiliation, vault)))
    }

    @Timed
    @PutMapping("/{vault}/secret/**")
    fun update(@PathVariable affiliation: String,
               @PathVariable vault: String,
               request: HttpServletRequest,
               @RequestBody fileContents: String,
               @RequestHeader(value = "AuroraConfigFileVersion", required = false) fileVersion: String = "",
               @RequestHeader(value = "AuroraValidateVersions", required = false) validateVersions: Boolean = true): Response {

        if (validateVersions && fileVersion.isEmpty()) {
            throw IllegalAccessException("Must specify AuroraConfigFileVersion header")
        }

        val path = "affiliation/$affiliation/vault/$vault/secret/**"
        val fileName = AntPathMatcher().extractPathWithinPattern(path, request.requestURI)

        val vault = facade.updateSecretFile(affiliation, vault, fileName, fileContents, fileVersion, validateVersions)
        return Response(items = listOf(vault))
    }

    @Timed
    @DeleteMapping("/{vault}")
    fun delete(@PathVariable affiliation: String, @PathVariable vault: String): Response {
        return Response(items = listOf(facade.delete(affiliation, vault)))
    }

}


