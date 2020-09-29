package no.skatteetaten.aurora.boober.service

import org.springframework.stereotype.Service

@Service
class IdService(private val herkimerService: HerkimerService) {
    fun generateOrFetchId(request: ApplicationDeploymentCreateRequest): String =
        herkimerService.createApplicationDeployment(request).id
}
