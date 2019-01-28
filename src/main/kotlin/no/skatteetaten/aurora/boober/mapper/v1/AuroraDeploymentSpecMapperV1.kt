package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraBuild
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploy
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.model.AuroraIntegration
import no.skatteetaten.aurora.boober.model.AuroraLocalTemplate
import no.skatteetaten.aurora.boober.model.AuroraRoute
import no.skatteetaten.aurora.boober.model.AuroraTemplate
import no.skatteetaten.aurora.boober.model.AuroraVolume
import no.skatteetaten.aurora.boober.utils.oneOf

class AuroraDeploymentSpecMapperV1(
    val applicationDeploymentRef: ApplicationDeploymentRef,
    val applicationFiles: List<AuroraConfigFile>
) {

    val configHandlers = applicationFiles.findConfigFieldHandlers()

    val handlers = listOf(
        AuroraConfigFieldHandler("splunkIndex"),
        AuroraConfigFieldHandler("message"),
        AuroraConfigFieldHandler("certificate/commonName"),
        AuroraConfigFieldHandler("certificate", defaultValue = false, canBeSimplifiedConfig = true),
        AuroraConfigFieldHandler("database", defaultValue = false, canBeSimplifiedConfig = true),
        AuroraConfigFieldHandler("prometheus", defaultValue = true, canBeSimplifiedConfig = true),
        AuroraConfigFieldHandler("prometheus/path", defaultValue = "/prometheus"),
        AuroraConfigFieldHandler("prometheus/port", defaultValue = 8081),
        AuroraConfigFieldHandler("management", defaultValue = true, canBeSimplifiedConfig = true),
        AuroraConfigFieldHandler("management/path", defaultValue = "actuator"),
        AuroraConfigFieldHandler("management/port", defaultValue = "8081"),
        AuroraConfigFieldHandler(
            "deployStrategy/type",
            defaultValue = "rolling",
            validator = { it.oneOf(listOf("recreate", "rolling")) }),
        AuroraConfigFieldHandler("deployStrategy/timeout", defaultValue = 180)
    ) + configHandlers

    fun createAuroraDeploymentSpec(
        auroraDeploymentSpec: AuroraDeploymentSpec,
        volume: AuroraVolume?,
        route: AuroraRoute?,
        build: AuroraBuild?,
        deploy: AuroraDeploy?,
        template: AuroraTemplate?,
        integration: AuroraIntegration?,
        localTemplate: AuroraLocalTemplate?,
        env: AuroraDeployEnvironment,
        applicationFile: AuroraConfigFile,
        configVersion: String,
        overrideFiles: Map<String, String>
    ): AuroraDeploymentSpecInternal {
        val name: String = auroraDeploymentSpec["name"]

        return AuroraDeploymentSpecInternal(
            applicationDeploymentRef = applicationDeploymentRef,
            schemaVersion = auroraDeploymentSpec["schemaVersion"],
            applicationPlatform = auroraDeploymentSpec["applicationPlatform"],
            type = auroraDeploymentSpec["type"],
            name = name,
            cluster = auroraDeploymentSpec["cluster"],
            environment = env,
            spec = auroraDeploymentSpec,
            volume = volume,
            route = route,
            build = build,
            deploy = deploy,
            template = template,
            localTemplate = localTemplate,
            integration = integration,
            applicationFile = applicationFile,
            configVersion = configVersion,
            overrideFiles = overrideFiles,
            message = auroraDeploymentSpec.getOrNull<String>("message"),
            env = auroraDeploymentSpec.getConfigEnv(configHandlers)
        )
    }
}
