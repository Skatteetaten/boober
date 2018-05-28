package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/clientconfig")
class ClientConfigControllerV1(
    @Value("\${boober.git.urlPattern}") val gitUrlPattern: String,
    @Value("\${openshift.cluster}") val openshiftCluster: String,
    @Value("\${openshift.url}") val openshiftUrl: String
) {

    @GetMapping()
    fun get(): Response {
        return Response(items = listOf(mapOf(
            Pair("gitUrlPattern", gitUrlPattern),
            Pair("openshiftCluster", openshiftCluster),
            Pair("openshiftUrl", openshiftUrl),
            Pair("apiVersion", 2)
        )))
    }
}