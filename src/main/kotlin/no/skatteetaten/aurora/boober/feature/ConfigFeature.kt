package no.skatteetaten.aurora.boober.feature

import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.addEnvVarsToMainContainers
import no.skatteetaten.aurora.boober.model.findConfigFieldHandlers
import org.springframework.stereotype.Service

@Service
class ConfigFeature : Feature {

    // TODO: implement in spec see belove in warnings method
    fun configHandlers(cmd: AuroraContextCommand) = cmd.applicationFiles.findConfigFieldHandlers()

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return configHandlers(cmd).toSet()
    }

    override fun createContext(spec: AuroraDeploymentSpec, cmd: AuroraContextCommand, validationContext: Boolean): Map<String, Any> {
        return mapOf("config" to configHandlers(cmd))
    }

    override fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: Map<String, Any>
    ) {
        val configHandlers = context["config"] as List<AuroraConfigFieldHandler>
        val env = adc.getConfigEnv(configHandlers).toEnvVars()
        resources.addEnvVarsToMainContainers(env, this::class.java)
    }

    // TODO: rewrite to associcateSubKeys
    fun envVarsKeysWithSpecialCharacters(adc: AuroraDeploymentSpec): List<String> {
        return adc.getSubKeys("config").keys.map {
            it.replace("config/", "")
        }.mapNotNull {
            val replaceValue = it.normalizeEnvVar()
            if (replaceValue == it) {
                null
            } else {
                "Config key=$it was normalized to $replaceValue"
            }
        }
    }
}
