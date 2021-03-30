package no.skatteetaten.aurora.boober.controller.v2

import mu.KotlinLogging
import no.skatteetaten.aurora.boober.controller.v1.getRefNameFromRequest
import no.skatteetaten.aurora.boober.facade.AuroraConfigFacade
import no.skatteetaten.aurora.boober.feature.applicationDeploymentRef
import no.skatteetaten.aurora.boober.feature.cluster
import no.skatteetaten.aurora.boober.feature.envName
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.utils.parallelMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2/multiaffiliation")
class MultiAffiliationControllerV2(
    private val auroraConfigFacade: AuroraConfigFacade
) {

    @GetMapping("/{environment}")
    fun findAll(@PathVariable environment: String): MultiAffiliationResponse {
        val refName = getRefNameFromRequest()
        // val refName = "feature/AOS-5477_hente_miljo_paa_tvers"

        val allApplications: List<String> = auroraConfigFacade.findAllAuroraConfigNames().parallelMap { aff ->
            try {
                logger.info("Searching {}", aff)
                val ref = AuroraConfigRef(aff, refName)
                auroraConfigFacade.findAllApplicationDeploymentSpecs(ref)
                    .filter { it.cluster == "utv" && it.envName == environment } // && it.testEnvironment
                    .map { "$aff/${it.applicationDeploymentRef}" }
            } catch (e: Exception) {
                logger.error(e.message)
                listOf("Error: $aff message: ${e.message}")
            }
        }.flatten()

        val items = allApplications.filter { s -> !s.startsWith("Error:") }
        val errors = allApplications.filter { s -> s.startsWith("Error:") }

        return MultiAffiliationResponse(items, errors)
    }
}

data class MultiAffiliationResponse(
    val items: List<String> = emptyList(),
    val itemsWithErrors: List<String> = emptyList()
)
