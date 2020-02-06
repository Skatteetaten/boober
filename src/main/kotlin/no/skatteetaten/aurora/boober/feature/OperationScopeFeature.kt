package no.skatteetaten.aurora.boober.feature

import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty("integrations.operations.scope")
class OperationScopeFeature(
    @Value("\${integrations.operations.scope}") val operationScopeConfiguration: String
) : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> =
        emptySet()

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {

        if (operationScopeConfiguration.isBlank()) return

        resources.forEach {
            modifyResource(it, "Added operationScope")
            it.resource.metadata.labels =
                mapOf("operationScope" to operationScopeConfiguration).addIfNotNull(it.resource.metadata?.labels)
        }
    }
}
