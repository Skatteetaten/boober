package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.utils.ConditionalOnPropertyMissingOrEmpty
import org.apache.commons.codec.digest.DigestUtils
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty("integrations.herkimer.url")
class IdService(
    private val herkimerService: HerkimerService,
    val configuration: HerkimerConfiguration
) {
    fun generateOrFetchId(request: ApplicationDeploymentCreateRequest): String =
        runCatching {
            herkimerService.createApplicationDeployment(request).id
        }.recover {
            configuration.fallback[request.name] ?: throw it
        }.getOrThrow()
}

@Service
@ConditionalOnPropertyMissingOrEmpty("integrations.herkimer.url")
class IdServiceFallback {

    fun generateOrFetchId(name: String, namespace: String): String =
        DigestUtils.sha1Hex("$namespace/$name")
}
