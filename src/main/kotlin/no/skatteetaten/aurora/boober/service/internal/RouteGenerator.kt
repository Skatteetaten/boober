package no.skatteetaten.aurora.boober.service.internal

import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newRoute
import com.fkorotkov.openshift.spec
import com.fkorotkov.openshift.tls
import com.fkorotkov.openshift.to
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.openshift.api.model.Route

object RouteGenerator {

    fun generateRoute(
        source: no.skatteetaten.aurora.boober.model.Route,
        serviceName: String,
        routeSuffix: String,
        routeLabels: Map<String, String>,
        ownerReference: OwnerReference,
        routeNamespace: String
    ): Route {
        return newRoute {
            metadata {
                name = source.objectName
                namespace = routeNamespace
                labels = routeLabels
                ownerReferences = listOf(ownerReference)
                source.annotations?.let {
                    annotations = it.mapKeys { kv -> kv.key.replace("|", "/") }
                }
            }
            spec {
                to {
                    kind = "Service"
                    name = serviceName
                }
                source.tls?.let {
                    tls {
                        insecureEdgeTerminationPolicy = it.insecurePolicy.name
                        termination = it.termination.name.toLowerCase()
                    }
                }
                host = "${source.host}$routeSuffix"
                source.path?.let {
                    path = it
                }
            }
        }
    }
}