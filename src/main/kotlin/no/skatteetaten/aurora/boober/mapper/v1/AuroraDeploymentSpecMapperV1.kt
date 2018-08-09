package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraBuild
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploy
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraIntegration
import no.skatteetaten.aurora.boober.model.AuroraLocalTemplate
import no.skatteetaten.aurora.boober.model.AuroraRoute
import no.skatteetaten.aurora.boober.model.AuroraTemplate
import no.skatteetaten.aurora.boober.model.AuroraVolume
import no.skatteetaten.aurora.boober.utils.oneOf

class AuroraDeploymentSpecMapperV1(val applicationId: ApplicationId) {

    val handlers = listOf(
        AuroraConfigFieldHandler("splunkIndex"),
        AuroraConfigFieldHandler("certificate/commonName"),
        AuroraConfigFieldHandler("certificate", subKeyFlag = true),
        AuroraConfigFieldHandler("database"),
        AuroraConfigFieldHandler("prometheus", defaultValue = true, subKeyFlag = true),
        AuroraConfigFieldHandler("prometheus/path", defaultValue = "/prometheus"),
        AuroraConfigFieldHandler("prometheus/port", defaultValue = 8081),
        AuroraConfigFieldHandler("management", defaultValue = true, subKeyFlag = true),
        AuroraConfigFieldHandler("management/path", defaultValue = "actuator"),
        AuroraConfigFieldHandler("management/port", defaultValue = "8081"),
        AuroraConfigFieldHandler(
            "deployStrategy/type",
            defaultValue = "rolling",
            validator = { it.oneOf(listOf("recreate", "rolling")) }),
        AuroraConfigFieldHandler("deployStrategy/timeout", defaultValue = 180)
    )

    fun createAuroraDeploymentSpec(
        auroraConfigFields: AuroraConfigFields,
        volume: AuroraVolume?,
        route: AuroraRoute?,
        build: AuroraBuild?,
        deploy: AuroraDeploy?,
        template: AuroraTemplate?,
        integration: AuroraIntegration?,
        localTemplate: AuroraLocalTemplate?,
        env: AuroraDeployEnvironment,
        applicationFile: AuroraConfigFile,
        configVersion: String
    ): AuroraDeploymentSpec {
        val name: String = auroraConfigFields.extract("name")

        return AuroraDeploymentSpec(
            applicationId = applicationId,
            schemaVersion = auroraConfigFields.extract("schemaVersion"),
            applicationPlatform = auroraConfigFields.extract("applicationPlatform"),
            type = auroraConfigFields.extract("type"),
            name = name,
            cluster = auroraConfigFields.extract("cluster"),
            environment = env,
            fields = auroraConfigFields.fields,
            volume = volume,
            route = route,
            build = build,
            deploy = deploy,
            template = template,
            localTemplate = localTemplate,
            integration = integration,
            applicationFile = applicationFile,
            configVersion = configVersion
        )
    }
}
