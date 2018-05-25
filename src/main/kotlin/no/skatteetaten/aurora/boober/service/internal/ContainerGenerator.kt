package no.skatteetaten.aurora.boober.service.internal

import com.fkorotkov.kubernetes.fieldRef
import com.fkorotkov.kubernetes.httpGet
import com.fkorotkov.kubernetes.newContainerPort
import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.kubernetes.newProbe
import com.fkorotkov.kubernetes.newVolumeMount
import com.fkorotkov.kubernetes.resources
import com.fkorotkov.kubernetes.securityContext
import com.fkorotkov.kubernetes.tcpSocket
import com.fkorotkov.kubernetes.valueFrom
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.IntOrStringBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.QuantityBuilder
import no.skatteetaten.aurora.boober.mapper.platform.AuroraContainer
import no.skatteetaten.aurora.boober.mapper.platform.volumeMount
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigResource
import no.skatteetaten.aurora.boober.model.Probe

object ContainerGenerator {
    fun create(adcContainer: AuroraContainer): Container {

        return auroraContainer {

            name = adcContainer.name
            ports = adcContainer.tcpPorts.map {
                newContainerPort {
                    name = it.key
                    containerPort = it.value
                    protocol = "TCP"
                }
            }

            adcContainer.image?.let {
                image = adcContainer.image
            }

            args = adcContainer.args

            val portEnv = adcContainer.tcpPorts.map {
                val portName = if (it.key == "http") "HTTP_PORT" else "${it.key}_HTTP_PORT".toUpperCase()
                EnvVarBuilder().withName(portName).withValue(it.value.toString()).build()
            }
            env = adcContainer.env + portEnv

            resources {
                limits = fromAdcResource(adcContainer.limit)
                requests = fromAdcResource(adcContainer.request)
            }

            volumeMounts = adcContainer.mounts.volumeMount()

            adcContainer.liveness?.let { probe ->
                livenessProbe = fromProbe(probe)
            }
            adcContainer.readiness?.let { probe ->
                readinessProbe = fromProbe(probe)
            }
        }
    }

    private fun fromAdcResource(resource: AuroraDeploymentConfigResource): Map<String, Quantity> = mapOf(
        "cpu" to quantity(resource.cpu),
        "memory" to quantity(resource.memory))

    private fun quantity(str: String) = QuantityBuilder().withAmount(str).build()

    private fun fromProbe(it: Probe): io.fabric8.kubernetes.api.model.Probe = newProbe {

        tcpSocket {
            port = IntOrStringBuilder().withIntVal(it.port).build()
        }
        it.path?.let {
            httpGet {
                path = it
            }
        }
        initialDelaySeconds = it.delay
        timeoutSeconds = it.timeout
    }

    fun auroraContainer(block: Container.() -> Unit = {}): Container {
        val instance = Container()
        instance.block()
        instance.terminationMessagePath = "/dev/termination-log"
        instance.imagePullPolicy = "IfNotPresent"
        instance.securityContext {
            privileged = false
        }
        instance.volumeMounts = listOf(newVolumeMount {
            name = "application-log-volume"
            mountPath = "/u01/logs"
        }) + instance.volumeMounts

        instance.env = listOf(
            newEnvVar {
                name = "POD_NAME"
                valueFrom {
                    fieldRef {
                        apiVersion = "v1"
                        fieldPath = "metadata.name"
                    }
                }
            },
            newEnvVar {
                name = "POD_NAMESPACE"
                valueFrom {
                    fieldRef {
                        apiVersion = "v1"
                        fieldPath = "metadata.namespace"
                    }
                }
            }
        ) + instance.env
        return instance
    }
}