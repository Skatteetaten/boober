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
                        validateOpenShiftReferences: Boolean = true): List<AuroraObjectsConfig> {

        return applicationIds.map { aid ->
            try {

                //for process flow some of the validation needs auroraConfig.
                //that is if the templateFile name is set in auroraConfig and does not exist in auroraConfig.

                val auroraDc = createAuroraDc(aid, auroraConfig, overrides)
                validateAuroraDc(aid, auroraDc, validateOpenShiftReferences)

                Result<AuroraObjectsConfig, Error?>(value = auroraDc)
            } catch (e: ApplicationConfigException) {
                Result<AuroraObjectsConfig, Error?>(error = Error(aid, e.errors))
                Result<AuroraDeploymentConfig, Error?>(error = Error(aid, e.errors))
            } catch (e: IllegalArgumentException) {
                Result<AuroraDeploymentConfig, Error?>(error = Error(aid, listOf(e.message!!)))
            }
        }.orElseThrow {
            AuroraConfigException("AuroraConfig contained errors for one or more applications", it)
        }
    }

    fun getAuroraConfigFields(aid: ApplicationId, allFiles: List<AuroraConfigFile>): Map<String, AuroraConfigField> {

        val configExtractors = allFiles.findExtractors("config")
        val parametersExtractors = allFiles.findExtractors("parameters")

        val mapper = AuroraDeploymentConfigMapperV1()
        val extractors = mapper.extractors + configExtractors + parametersExtractors
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

    fun createAuroraDc(aid: ApplicationId, auroraConfig: AuroraConfig, overrides: List<AuroraConfigFile>): AuroraObjectsConfig {

        val allFiles: List<AuroraConfigFile> = auroraConfig.getFilesForApplication(aid, overrides)
        val fields: Map<String, AuroraConfigField> = getAuroraConfigFields(aid, allFiles)
        val type = fields.extract("type", { TemplateType.valueOf(it.textValue()) })

        val groupId = fields.extract("groupId")
        val artifactId = fields.extract("artifactId")
        val name = fields.extractOrDefault("name", artifactId)

        if (type == TemplateType.process) {

            val templateFile = fields.extractOrNull("templateFile")?.let { fileName ->
                auroraConfig.auroraConfigFiles.find { it.name == fileName }?.contents
            }

            return AuroraProcessConfig(
                    schemaVersion = fields.extract("schemaVersion"),
                    affiliation = fields.extract("affiliation"),
                    cluster = fields.extract("cluster"),
                    type = type,
                    name = name,
                    envName = fields.extractOrDefault("envName", aid.environmentName),
                    permissions = Permissions(Permission(
                            fields.extractOrNull("permissions/admin/groups", { it.textValue().split(" ").toSet() }),
                            fields.extractOrNull("permissions/admin/users", { it.textValue().split(" ").toSet() })
                    )),
                    secrets = fields.extractOrNull("secretFolder", {
                        auroraConfig.getSecrets(it.asText())
                    }),

                    config = getConfigMap(fields, allFiles.findExtractors("config")),
                    template = fields.extractOrNull("template"),
                    templateFile = templateFile,
                    parameters = getParameters(fields, allFiles.findExtractors("parameters")),
                    flags = AuroraProcessConfigFlags(
                            fields.extract("flags/route", { it.asText() == "true" })
                    )
            )

        } else {

            return AuroraDeploymentConfig(
                    schemaVersion = fields.extract("schemaVersion"),

                    affiliation = fields.extract("affiliation"),
                    cluster = fields.extract("cluster"),
                    type = type,
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

                    webseal = fields.findAll("webseal", {
                        Webseal(
                                it.extract("webseal/path"),
                                it.extractOrNull("webseal/roles")
                        )
                    }),

                    prometheus = fields.findAll("prometheus", {
                        HttpEndpoint(
                                it.extract("prometheus/path"),
                                it.extractOrNull("prometheus/port", JsonNode::asInt)
                        )
                    }),

                    secrets = fields.extractOrNull("secretFolder", {
                        auroraConfig.getSecrets(it.asText())
                    }),

                    config = getConfigMap(fields, allFiles.findExtractors("config"))
            )
        }
    }

    private fun validateAuroraDc(aid: ApplicationId, auroraDc: AuroraObjectsConfig, validateOpenShiftReferences: Boolean) {

        val errors = mutableListOf<Exception>()

        if (auroraDc.secrets != null && (auroraDc.secrets as Map<String, String>).isEmpty()) {
            errors.add(IllegalArgumentException("Missing secret files"))
        }

        if (!Regex("^[a-z][-a-z0-9]{0,23}[a-z0-9]$").matches(auroraDc.name)) {
            errors.add(IllegalArgumentException("Name [${auroraDc.name}] is not valid DNS952 label. 24 length alphanumeric."))
        }

        if (auroraDc is AuroraProcessConfig) {

            if (auroraDc.template == null && auroraDc.templateFile == null) {
                errors.add(IllegalArgumentException("Specify either template or templateFile"))
            } else if (auroraDc.template != null && !openShiftClient.templateExist(auroraDc.template)) {
                errors.add(IllegalArgumentException("Template ${auroraDc.template} does not exist in openshift namespace"))
            }
        }

        //TODO i do not like this. If you want to test this just mock it.
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

    private fun getParameters(fields: Map<String, AuroraConfigField>,
                              parameterExtractors: List<AuroraConfigFieldHandler>): Map<String, String>? {

        return parameterExtractors.map {
            val (_, field) = it.name.split("/")

            val value = fields.extract(it.name)
            field to value
        }.toMap()

    }
}
