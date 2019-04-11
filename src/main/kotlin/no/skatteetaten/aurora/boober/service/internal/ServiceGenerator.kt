package no.skatteetaten.aurora.boober.service.internal

import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newService
import com.fkorotkov.kubernetes.newServicePort
import com.fkorotkov.kubernetes.spec
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.Service
import no.skatteetaten.aurora.boober.mapper.v1.PortNumbers
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.utils.addIfNotNull

object ServiceGenerator {

    fun generateService(
        auroraDeploymentSpecInternal: AuroraDeploymentSpecInternal,
        serviceLabels: Map<String, String>,
        reference: OwnerReference,
        podPort: Int
    ): Service? {
        return auroraDeploymentSpecInternal.deploy?.let { deploy ->

            val webseal = auroraDeploymentSpecInternal.integration?.webseal?.let {
                val host = it.host
                    ?: "${auroraDeploymentSpecInternal.name}-${auroraDeploymentSpecInternal.environment.namespace}"
                "sprocket.sits.no/service.webseal" to host
            }

            val websealRoles = auroraDeploymentSpecInternal.integration?.webseal?.roles?.let {
                "sprocket.sits.no/service.webseal-roles" to it
            }

            val prometheusAnnotations = deploy.prometheus?.takeIf { it.path != "" }?.let {
                mapOf(
                    "prometheus.io/scheme" to "http",
                    "prometheus.io/scrape" to "true",
                    "prometheus.io/path" to it.path,
                    "prometheus.io/port" to "${it.port}"
                )
            } ?: mapOf("prometheus.io/scrape" to "false")
            newService {
                metadata {
                    ownerReferences = listOf(reference)
                    name = auroraDeploymentSpecInternal.name
                    namespace = auroraDeploymentSpecInternal.environment.namespace
                    annotations = prometheusAnnotations.addIfNotNull(webseal).addIfNotNull(websealRoles)
                    labels = serviceLabels
                }

                spec {
                    ports = listOf(
                        newServicePort {
                            name = "http"
                            protocol = "TCP"
                            port = PortNumbers.HTTP_PORT
                            targetPort = IntOrString(podPort)
                            nodePort = 0
                        }
                    )

                    selector = mapOf("name" to auroraDeploymentSpecInternal.name)
                    type = "ClusterIP"
                    sessionAffinity = "None"
                }
            }
        }
    }
}