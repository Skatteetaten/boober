package no.skatteetaten.aurora.boober.controller

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.facade.VaultFacade
import no.skatteetaten.aurora.boober.model.AuroraSecretVault
import org.springframework.util.AntPathMatcher
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.servlet.http.HttpServletRequest

@RestController
@RequestMapping("/affiliation/{affiliation}/vault")
class VaultController(val facade: VaultFacade) {


    @GetMapping()
    fun listVaults(@PathVariable affiliation: String): Response {
        return Response(items = facade.listVaults(affiliation))
    }

    @PutMapping()
    fun save(@PathVariable affiliation: String,
             @RequestBody vault: AuroraSecretVault,
             @RequestHeader(value = "AuroraValidateVersions", required = false) validateVersions: Boolean = true): Response {
        return Response(items = listOf(facade.save(affiliation, vault, validateVersions)))
    }

    @GetMapping("/{vault}")
    fun get(@PathVariable affiliation: String, @PathVariable vault: String): Response {
        return Response(items = listOf(facade.find(affiliation, vault)))
    }

    @PutMapping("/{vault}/secret/**")
    fun update(@PathVariable affiliation: String,
               @PathVariable vault: String,
               request: HttpServletRequest,
               @RequestBody fileContents: String,
               @RequestHeader(value = "AuroraConfigFileVersion", required = false) fileVersion: String = "",
               @RequestHeader(value = "AuroraValidateVersions", required = false) validateVersions: Boolean = true): Response {

        if(validateVersions && fileVersion.isEmpty()) {
            throw IllegalAccessException("Must specify AuroraConfigFileVersion header");
        }

        val path = "affiliation/$affiliation/vault/$vault/secret/**"
        val fileName = AntPathMatcher().extractPathWithinPattern(path, request.requestURI)

        val vault = facade.updateSecretFile(affiliation, vault, fileName, fileContents, fileVersion, validateVersions)
        return Response(items = listOf(vault))
    }

    @DeleteMapping("/{vault}")
    fun delete(@PathVariable affiliation: String, @PathVariable vault: String): Response {
        return Response(items = listOf(facade.delete(affiliation, vault)))
    }

}


