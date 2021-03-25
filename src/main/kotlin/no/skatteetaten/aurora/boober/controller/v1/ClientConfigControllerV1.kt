package no.skatteetaten.aurora.boober.controller.v1

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import no.skatteetaten.aurora.boober.controller.internal.Response

@RestController
@RequestMapping("/v1/clientconfig")
class ClientConfigControllerV1(
    @Value("\${integrations.aurora.config.git.urlPattern}") val gitUrlPattern: String,
    @Value("\${openshift.cluster}") val openshiftCluster: String,
    @Value("\${integrations.openshift.url}") val openshiftUrl: String
) {

    @GetMapping
    fun get() = Response(
        items = listOf(
            mapOf(
                Pair("gitUrlPattern", gitUrlPattern),
                Pair("openshiftCluster", openshiftCluster),
                Pair("openshiftUrl", openshiftUrl),
                Pair("apiVersion", 2)
            )
        )
    )
}
