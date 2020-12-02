package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.exec
import com.fkorotkov.kubernetes.newLifecycle
import com.fkorotkov.kubernetes.preStop
import com.fkorotkov.kubernetes.resources
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.utils.allNonSideCarContainers
import no.skatteetaten.aurora.boober.utils.containersWithName
import no.skatteetaten.aurora.boober.utils.durationString
import org.springframework.boot.convert.DurationStyle
import org.springframework.stereotype.Service
import java.time.Duration

const val PRE_STOP_DURATION: String = "lifecycle/preStopDuration"

val AuroraDeploymentSpec.preStop: Duration? get() = this.getOrNull<String>(PRE_STOP_DURATION)?.let { DurationStyle.SIMPLE.parse(it) }

@Service
class PreStopFeature : Feature {

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler(PRE_STOP_DURATION, validator = { it.durationString() })
        )
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {

        val preStop: Duration = adc.preStop ?: return

        val containerLifcecyle = newLifecycle {
            preStop {
                exec {
                    command = listOf("sh", "-c", "sleep ${preStop.seconds}s")
                }
            }
        }

        val logginDuration: Duration = preStop + preStop

        val loggingContainerLifecycle = newLifecycle {
            preStop {
                exec {
                    command = listOf("sh", "-c", "sleep ${logginDuration.seconds}s")
                }
            }
        }

        resources.forEach {
            it.resource.allNonSideCarContainers.forEach { container ->
                modifyResource(it, "Added preStop exec")
                // TODO: this will overwrite if we have templates with lifecycle?
                container.lifecycle = containerLifcecyle
            }

            it.resource.containersWithName(adc.fluentSideCarContainerName).forEach { container ->
                modifyResource(it, "Added double preStop exec")
                container.lifecycle = loggingContainerLifecycle
            }
        }
    }
}
