package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.internal.*
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.validation.*
import org.springframework.stereotype.Service

@Service
class AuroraDeploymentConfigService(val openShiftClient: OpenShiftClient) {

    fun validate(auroraConfig: AuroraConfig) {
        val appIds = auroraConfig.getApplicationIds()
        // Verify that all AuroraDeploymentConfigs represented by the AuroraConfig are valid
        createAuroraDcs(auroraConfig, appIds)
    }

    fun createAuroraDcs(auroraConfig: AuroraConfig,
                        applicationIds: List<ApplicationId>,
                        overrides: List<AuroraConfigFile> = listOf(),
                        validateOpenShiftReferences: Boolean = true): List<AuroraDeploymentConfig> {

        return applicationIds.map { aid ->
            try {
                val auroraDc = createAuroraDc(aid, auroraConfig, overrides)
                validateAuroraDc(aid, auroraDc, validateOpenShiftReferences)

                Result<AuroraDeploymentConfig, Error?>(value = auroraDc)
            } catch (e: ApplicationConfigException) {
                Result<AuroraDeploymentConfig, Error?>(error = Error(aid, e.errors))
            }
        }.orElseThrow {
            AuroraConfigException("AuroraConfig contained errors for one or more applications", it)
        }
    }

    fun getAuroraConfigFields(aid: ApplicationId, allFiles: List<AuroraConfigFile>): Map<String, AuroraConfigField> {

        val configExtractors = allFiles.findConfigExtractors()

        val mapper = AuroraDeploymentConfigMapperV1()
        val extractors = mapper.extractors + configExtractors
        val fields = extractors.extractFrom(allFiles)

        extractors.mapNotNull { e -> e.validator(fields[e.name]?.value) }
                .takeIf { it.isNotEmpty() }
                ?.let {
                    throw ApplicationConfigException(
                            "Config for application ${aid.applicationName} in environment ${aid.environmentName} contains errors",
                            errors = it.mapNotNull { it.message })
                }

        return fields
    }

    fun createAuroraDc(aid: ApplicationId, auroraConfig: AuroraConfig, overrides: List<AuroraConfigFile>): AuroraDeploymentConfig {

        val allFiles: List<AuroraConfigFile> = auroraConfig.getFilesForApplication(aid, overrides)
        val fields: Map<String, AuroraConfigField> = getAuroraConfigFields(aid, allFiles)

        val groupId = fields.extract("groupId")
        val artifactId = fields.extract("artifactId")
        val name = fields.extractOrDefault("name", artifactId)

        return AuroraDeploymentConfig(
                schemaVersion = fields.extract("schemaVersion"),

                affiliation = fields.extract("affiliation"),
                cluster = fields.extract("cluster"),
                type = fields.extract("type", { TemplateType.valueOf(it.textValue()) }),

                name = name,
                envName = fields.extractOrDefault("envName", aid.environmentName),

                groupId = groupId,
                artifactId = artifactId,
                version = fields.extract("version"),

                replicas = fields.extract("replicas", JsonNode::asInt),
                extraTags = fields.extract("extraTags"),

                flags = AuroraDeploymentConfigFlags(
                        fields.extract("flags/route", { it.asText() == "true" }),
                        fields.extract("flags/cert", { it.asText() == "true" }),
                        fields.extract("flags/debug", { it.asText() == "true" }),
                        fields.extract("flags/alarm", { it.asText() == "true" }),
                        fields.extract("flags/rolling", { it.asText() == "true" })
                ),
                resources = AuroraDeploymentConfigResources(
                        memory = AuroraDeploymentConfigResource(
                                min = fields.extract("resources/memory/min"),
                                max = fields.extract("resources/memory/max")
                        ),
                        cpu = AuroraDeploymentConfigResource(
                                min = fields.extract("resources/cpu/min"),
                                max = fields.extract("resources/cpu/max")
                        )
                ),
                permissions = Permissions(Permission(
                        fields.extractOrNull("permissions/admin/groups", { it.textValue().split(" ").toSet() }),
                        fields.extractOrNull("permissions/admin/users", { it.textValue().split(" ").toSet() })
                )),

                splunkIndex = fields.extractOrNull("splunkIndex"),
                database = fields.extractOrNull("database"),
                managementPath = fields.extractOrNull("managementPath"),

                certificateCn = fields.extractOrDefault("certificateCn", "$groupId.$name"),

                webseal = getWebSEAL(fields),
                prometheus = getPrometheus(fields),

                secrets = getSecrets(fields, auroraConfig),
                config = getConfigMap(fields, allFiles.findConfigExtractors())
        )
    }

    private fun validateAuroraDc(aid: ApplicationId, auroraDc: AuroraDeploymentConfig, validateOpenShiftReferences: Boolean) {

        val errors = mutableListOf<Exception>()

        if (auroraDc.secrets != null && auroraDc.secrets.isEmpty()) {
            errors.add(IllegalArgumentException("Missing secret files"))
        }

        if (auroraDc.type == TemplateType.process) {
            errors.add(IllegalArgumentException("Not handled yet"))
        }

        if (!Regex("^[a-z][-a-z0-9]{0,23}[a-z0-9]$").matches(auroraDc.name)) {
            errors.add(IllegalArgumentException("Name [${auroraDc.name}] is not valid DNS952 label. 24 length alphanumeric."))
        }

        if (validateOpenShiftReferences) {
            auroraDc.permissions.admin.groups
                    ?.filter { !openShiftClient.isValidGroup(it) }
                    .takeIf { it != null && it.isNotEmpty() }
                    ?.let { errors.add(AuroraConfigException("The following groups are not valid=${it.joinToString()}")) }

            auroraDc.permissions.admin.users
                    ?.filter { !openShiftClient.isValidUser(it) }
                    .takeIf { it != null && it.isNotEmpty() }
                    ?.let { errors.add(AuroraConfigException("The following users are not valid=${it.joinToString()}")) }
        }

        errors.takeIf { it.isNotEmpty() }?.let {
            throw ApplicationConfigException("Config for application ${aid.applicationName} in environment ${aid.environmentName} contains errors", errors = it.mapNotNull { it.message })
        }
    }

    private fun getSecrets(fields: Map<String, AuroraConfigField>, auroraConfig: AuroraConfig): Map<String, String>? {

        return if (fields.containsKey("secretFolder")) {
            auroraConfig.getSecrets(fields.extract("secretFolder"))
        } else null
    }

    private fun getConfigMap(fields: Map<String, AuroraConfigField>,
                             configExtractors: List<AuroraConfigFieldHandler>): Map<String, Map<String, String>>? {

        val configMap: MutableMap<String, MutableMap<String, String>> = mutableMapOf()

        configExtractors.forEach {
            val (_, configFile, field) = it.name.split("/")

            val value = fields.extract(it.name)
            val keyValue = mutableMapOf(field to value)

            if (configMap.containsKey(configFile)) configMap[configFile]?.putAll(keyValue)
            else configMap.put(configFile, keyValue)
        }

        return if (configMap.isNotEmpty()) configMap else null
    }

    private fun getWebSEAL(fields: Map<String, AuroraConfigField>): Webseal? {

        return if (fields.containsKey("webseal/path")) {
            Webseal(
                    fields.extract("webseal/path"),
                    fields.extractOrNull("webseal/roles")
            )
        } else null
    }

    private fun getPrometheus(fields: Map<String, AuroraConfigField>): HttpEndpoint? {
        return if (fields.containsKey("prometheus/path")) {
            HttpEndpoint(
                    fields.extract("prometheus/path"),
                    fields.extract("prometheus/port", JsonNode::asInt)
            )
        } else null
    }
}
