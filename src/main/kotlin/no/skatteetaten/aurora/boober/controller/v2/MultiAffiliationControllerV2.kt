package no.skatteetaten.aurora.boober.controller.v2

import mu.KotlinLogging
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.v1.getRefNameFromRequest
import no.skatteetaten.aurora.boober.facade.AuroraConfigFacade
import no.skatteetaten.aurora.boober.feature.applicationDeploymentRef
import no.skatteetaten.aurora.boober.feature.cluster
import no.skatteetaten.aurora.boober.feature.envName
import no.skatteetaten.aurora.boober.model.toAdr
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
    fun findAll(@PathVariable environment: String): Response {
        val refName = getRefNameFromRequest()
        // val refName = "feature/AOS-5477_hente_miljo_paa_tvers"

        val allApplications: List<MultiAffiliationResponse> =
            auroraConfigFacade.findAllAuroraConfigNames().parallelMap { aff ->
                try {
                    logger.info("Searching {}", aff)
                    val ref = AuroraConfigRef(aff, refName)
                    auroraConfigFacade.findAllApplicationDeploymentSpecs(ref)
                        .filter {
                            it.cluster == "utv" &&
                                it.applicationDeploymentRef.toAdr().environment == environment
                        } // && it.testEnvironment
                        .map {
                            MultiAffiliationResponse(
                                affiliation = aff,
                                applicationDeplymentRef = it.applicationDeploymentRef,
                                warningMessage = if (it.applicationDeploymentRef.toAdr().environment != it.envName) {
                                    "Divergent envName: ${it.envName}"
                                } else {
                                    null
                                }
                            )
                        }
                } catch (e: Exception) {
                    logger.info(e.message)
                    listOf(MultiAffiliationResponse(affiliation = aff, success = false, errorMessage = e.message))
                }
            }.flatten()

        return Response(success = true, message = "OK.", items = allApplications, count = allApplications.size)
    }
}

data class MultiAffiliationResponse(
    var affiliation: String,
    var applicationDeplymentRef: String? = null,
    val success: Boolean = true,
    var errorMessage: String? = null,
    var warningMessage: String? = null
)
