package no.skatteetaten.aurora.boober.service.internal

import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newRoute
import com.fkorotkov.openshift.spec
import com.fkorotkov.openshift.to
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.openshift.api.model.Route

object RouteGenerator {

    fun generateRoute(
        source: no.skatteetaten.aurora.boober.model.Route,
        serviceName: String,
        routeSuffix: String,
        routeLabels: Map<String, String>,
        ownerReference: OwnerReference
    ): Route {
        return newRoute {
            apiVersion = "v1"
            metadata {
                name = source.objectName
                labels = routeLabels
                ownerReferences = listOf(ownerReference)
                source.annotations?.let {
                    annotations = it.mapKeys { it.key.replace("|", "/") }
                }
            }
            spec {
                to {
                    kind = "Service"
                    name = serviceName
                }
                host = "${source.host}$routeSuffix"
                source.path?.let {
                    path = it
                }
            }
        }
    }
}