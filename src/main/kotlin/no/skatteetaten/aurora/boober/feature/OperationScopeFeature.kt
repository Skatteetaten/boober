package no.skatteetaten.aurora.boober.feature

import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.addLabels
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

    override fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: Map<String, Any>
    ) {

        if (operationScopeConfiguration.isBlank()) {
            return
        }

        resources.addLabels(
            commonLabels = mapOf("operationScope" to operationScopeConfiguration),
            comment = "Added operationScope label",
            clazz = this::class.java
        )
    }
}
