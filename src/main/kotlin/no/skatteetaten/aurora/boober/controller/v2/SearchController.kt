package no.skatteetaten.aurora.boober.controller.v2

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import no.skatteetaten.aurora.boober.controller.internal.ErrorsResponse
import no.skatteetaten.aurora.boober.controller.v1.getRefNameFromRequest
import no.skatteetaten.aurora.boober.facade.AuroraConfigFacade

@RestController
@RequestMapping("/v2/search")
class SearchController(
    private val auroraConfigFacade: AuroraConfigFacade
) {

    @GetMapping
    fun findAll(@RequestParam(name = "environment") environment: String): ErrorsResponse {
        val refName = getRefNameFromRequest()

        val searchForApplications = auroraConfigFacade.searchForApplications(refName, environment)
        val successList = searchForApplications.filter { it.errorMessage == null }
        val errorsList = searchForApplications.filter { it.errorMessage != null }

        return ErrorsResponse(
            success = true,
            message = "OK.",
            items = successList,
            count = successList.size + errorsList.size,
            errors = errorsList
        )
    }
}
