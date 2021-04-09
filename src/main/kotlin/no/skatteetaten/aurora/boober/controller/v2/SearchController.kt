package no.skatteetaten.aurora.boober.controller.v2

import com.fasterxml.jackson.annotation.JsonInclude
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.controller.internal.ErrorsResponse
import no.skatteetaten.aurora.boober.controller.v1.getRefNameFromRequest
import no.skatteetaten.aurora.boober.facade.AuroraConfigFacade
import no.skatteetaten.aurora.boober.feature.applicationDeploymentRef
import no.skatteetaten.aurora.boober.feature.cluster
import no.skatteetaten.aurora.boober.feature.envAutoDeploy
import no.skatteetaten.aurora.boober.feature.envName
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.toAdr
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.utils.parallelMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2/search")
class SearchController(
    private val auroraConfigFacade: AuroraConfigFacade
) {

    @GetMapping
    fun findAll(@RequestParam(name = "environment") environment: String): ErrorsResponse {
        val refName = getRefNameFromRequest()
        // val refName = "feature/AOS-5477_hente_miljo_paa_tvers"

        val allApplications: List<MultiAffiliationResponse> =
            auroraConfigFacade.findAllAuroraConfigNames().parallelMap { aff ->
                try {
                    val ref = AuroraConfigRef(aff, refName)
                    auroraConfigFacade.findAllApplicationDeploymentSpecs(ref, environment)
                        .filter {
                            it.cluster == "utv"
                        }
                        .map {
                            val applicationDeploymentRef = it.applicationDeploymentRef.toAdr()

                            MultiAffiliationResponse(
                                affiliation = aff,
                                autoDeploy = it.envAutoDeploy,
                                applicationDeploymentRef = applicationDeploymentRef,
                                warningMessage = if (applicationDeploymentRef.environment != it.envName) {
                                    "Environment name is overwritten in config file. ApplicationDeploymentRef.environment: ${applicationDeploymentRef.environment}, AuroraDeploymentSpec.envName: ${it.envName}"
                                } else {
                                    null
                                }
                            )
                        }
                } catch (e: Exception) {
                    logger.info(e.message)
                    listOf(MultiAffiliationResponse(affiliation = aff, errorMessage = e.message))
                }
            }.flatten()

        val successList = allApplications.filter { it.errorMessage == null }
        val errorsList = allApplications.filter { it.errorMessage != null }

        return ErrorsResponse(
            success = true,
            message = "OK.",
            items = successList,
            count = successList.size + errorsList.size,
            errors = errorsList
        )
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MultiAffiliationResponse(
    val affiliation: String,
    val autoDeploy: Boolean? = null,
    val applicationDeploymentRef: ApplicationDeploymentRef? = null,
    val errorMessage: String? = null,
    val warningMessage: String? = null
)
