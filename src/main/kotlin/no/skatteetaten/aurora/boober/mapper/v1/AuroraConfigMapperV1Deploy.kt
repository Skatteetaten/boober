package no.skatteetaten.aurora.boober.mapper.v1

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigDeploy
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigFlags
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigResource
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigResources
import no.skatteetaten.aurora.boober.model.AuroraSecretVault
import no.skatteetaten.aurora.boober.model.DeployCommand
import no.skatteetaten.aurora.boober.model.HttpEndpoint
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.model.Webseal
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.utils.length
import no.skatteetaten.aurora.boober.utils.notBlank


class AuroraConfigMapperV1Deploy(
        aid: DeployCommand,
        auroraConfig: AuroraConfig,
        openShiftClient: OpenShiftClient,
        vaults: Map<String, AuroraSecretVault>
) : AuroraConfigMapperV1(aid, auroraConfig, openShiftClient, vaults) {

    val handlers = listOf(
            AuroraConfigFieldHandler("flags/cert", defaultValue = "false"),
            AuroraConfigFieldHandler("flags/debug", defaultValue = "false"),
            AuroraConfigFieldHandler("flags/alarm", defaultValue = "true"),
            AuroraConfigFieldHandler("flags/rolling", defaultValue = "false"),
            AuroraConfigFieldHandler("resources/cpu/min", defaultValue = "0"),
            AuroraConfigFieldHandler("resources/cpu/max", defaultValue = "2000m"),
            AuroraConfigFieldHandler("resources/memory/min", defaultValue = "128Mi"),
            AuroraConfigFieldHandler("resources/memory/max", defaultValue = "256Mi"),
            AuroraConfigFieldHandler("replicas", defaultValue = "1"),
            AuroraConfigFieldHandler("groupId", validator = { it.length(200, "GroupId must be set and be shorter then 200 characters") }),
            AuroraConfigFieldHandler("artifactId", validator = { it.length(50, "ArtifactId must be set and be shorter then 50 characters") }),
            AuroraConfigFieldHandler("version", validator = { it.notBlank("Version must be set") }),
            AuroraConfigFieldHandler("extraTags", defaultValue = "latest,major,minor,patch"),
            AuroraConfigFieldHandler("splunkIndex"),
            AuroraConfigFieldHandler("serviceAccount"),
            AuroraConfigFieldHandler("prometheus/path", defaultValue = "/prometheus"),
            AuroraConfigFieldHandler("prometheus/port", defaultValue = "8081"),
            AuroraConfigFieldHandler("managementPath", defaultValue = ":8081/actuator"),
            AuroraConfigFieldHandler("certificateCn")
    )

    override val fieldHandlers = v1Handlers + handlers
    override val auroraConfigFields = AuroraConfigFields.create(fieldHandlers, applicationFiles)

    override fun toAuroraDeploymentConfig(): AuroraDeploymentConfig {

        val groupId = auroraConfigFields.extract("groupId")
        val name = auroraConfigFields.extract("name")

        val certFlag = auroraConfigFields.extract("flags/cert", { it.asText() == "true" })
        val certificateCnDefault = if (certFlag) "$groupId.$name" else null

        return AuroraDeploymentConfigDeploy(
                schemaVersion = auroraConfigFields.extract("schemaVersion"),

                affiliation = auroraConfigFields.extract("affiliation"),
                cluster = auroraConfigFields.extract("cluster"),
                type = auroraConfigFields.extract("type", { TemplateType.valueOf(it.textValue()) }),
                name = name,
                envName = auroraConfigFields.extractOrDefault("envName", deployCommand.applicationId.environment),

                groupId = groupId,
                artifactId = auroraConfigFields.extract("artifactId"),
                version = auroraConfigFields.extract("version"),

                replicas = auroraConfigFields.extract("replicas", JsonNode::asInt),
                extraTags = auroraConfigFields.extract("extraTags"),

                flags = AuroraDeploymentConfigFlags(
                        certFlag,
                        auroraConfigFields.extract("flags/debug", { it.asText() == "true" }),
                        auroraConfigFields.extract("flags/alarm", { it.asText() == "true" }),
                        auroraConfigFields.extract("flags/rolling", { it.asText() == "true" })

                ),
                resources = AuroraDeploymentConfigResources(
                        memory = AuroraDeploymentConfigResource(
                                min = auroraConfigFields.extract("resources/memory/min"),
                                max = auroraConfigFields.extract("resources/memory/max")
                        ),
                        cpu = AuroraDeploymentConfigResource(
                                min = auroraConfigFields.extract("resources/cpu/min"),
                                max = auroraConfigFields.extract("resources/cpu/max")
                        )
                ),
                permissions = extractPermissions(),
                splunkIndex = auroraConfigFields.extractOrNull("splunkIndex"),
                database = auroraConfigFields.getDatabases(dbHandlers),
                managementPath = auroraConfigFields.extractOrNull("managementPath"),

                certificateCn = auroraConfigFields.extractOrDefault("certificateCn", certificateCnDefault),

                webseal = auroraConfigFields.findAll("webseal", {
                    Webseal(
                            auroraConfigFields.extract("webseal/host"),
                            auroraConfigFields.extractOrNull("webseal/roles")
                    )
                }),

                prometheus = auroraConfigFields.findAll("prometheus", {
                    HttpEndpoint(
                            auroraConfigFields.extract("prometheus/path"),
                            auroraConfigFields.extractOrNull("prometheus/port", JsonNode::asInt)
                    )
                }),

                secrets = auroraConfigFields.extractOrNull("secretVault", {
                    vaults[it.asText()]?.secrets
                }),

                config = auroraConfigFields.getConfigMap(configHandlers),
                route = getRoute(name),
                serviceAccount = auroraConfigFields.extractOrNull("serviceAccount"),
                mounts = auroraConfigFields.getMounts(mountHandlers, vaults),
                releaseTo = auroraConfigFields.extractOrNull("releaseTo"),
                fields = auroraConfigFields.fields,
                unmappedPointers = getUnmappedPointers(),
                applicationFile = applicationFile.name,
                overrideFiles = overrideFiles
        )
    }


}