package no.skatteetaten.aurora.boober.service

import org.springframework.stereotype.Service

data class ApplicationDeploymentCreateRequest(
    val name: String,
    val environmentName: String,
    val cluster: String,
    val businessGroup: String
)

@Service
class IdService(private val herkimerService: HerkimerService) {
    fun generateOrFetchId(request: ApplicationDeploymentCreateRequest): String =
        herkimerService.createApplicationDeployment(request).id
}
