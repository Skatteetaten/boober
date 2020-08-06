package no.skatteetaten.aurora.boober.controller.v2

import no.skatteetaten.aurora.boober.service.AzureService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v2/group")
class GroupController(
    val azureService: AzureService
) {

    @GetMapping("/{name}")
    fun getGroup(@PathVariable name: String): String {
        return azureService.resolveGroupName(name)
    }
}