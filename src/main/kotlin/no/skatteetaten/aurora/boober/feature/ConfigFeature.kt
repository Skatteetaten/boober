package no.skatteetaten.aurora.boober.feature

import org.springframework.stereotype.Service
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.addEnvVarsToMainContainers
import no.skatteetaten.aurora.boober.model.findConfigFieldHandlers

private const val CONFIG_CONTEXT_KEY = "config"

private val FeatureContext.configFieldHandlers: List<AuroraConfigFieldHandler>
    get() = this.getContextKey(
        CONFIG_CONTEXT_KEY
    )

@Service
class ConfigFeature : Feature {

    // TODO: implement in spec see belove in warnings method
    fun configHandlers(cmd: AuroraContextCommand) = cmd.applicationFiles.findConfigFieldHandlers()

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return configHandlers(cmd).toSet()
    }

    override fun createContext(
        spec: AuroraDeploymentSpec,
        cmd: AuroraContextCommand,
        validationContext: Boolean
    ): Map<String, Any> {
        return mapOf(CONFIG_CONTEXT_KEY to configHandlers(cmd))
    }

    override fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: FeatureContext
    ) {
        val configHandlers = context.configFieldHandlers
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
