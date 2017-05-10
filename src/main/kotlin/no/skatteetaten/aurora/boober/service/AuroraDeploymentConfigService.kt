package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.internal.*
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.validation.AuroraDeploymentConfigMapperV1
import no.skatteetaten.aurora.boober.service.validation.extract
import no.skatteetaten.aurora.boober.service.validation.extractFrom
import no.skatteetaten.aurora.boober.service.validation.findConfigExtractors
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
            val result: Result<AuroraDeploymentConfig, Error?> = try {
                Result(value = createAuroraDc(auroraConfig, aid, overrides, validateOpenShiftReferences))
            } catch (e: ApplicationConfigException) {
                Result(error = Error(aid, e.errors))
            }
            result
        }.orElseThrow {
            AuroraConfigException("AuroraConfig contained errors for one or more applications", it)
        }
    }

    fun createAuroraDc(auroraConfig: AuroraConfig,
                       aid: ApplicationId,
                       overrides: List<AuroraConfigFile> = listOf(),
                       validateOpenShiftReferences: Boolean = true): AuroraDeploymentConfig {

        val allFiles = auroraConfig.getFilesForApplication(aid, overrides).reversed()

        val configExtractors = allFiles.findConfigExtractors()

        val mapper = AuroraDeploymentConfigMapperV1()

        val extractors = mapper.extractors + configExtractors
        val fields = extractors.extractFrom(allFiles)


        val secrets = if (fields.containsKey("secretFolder")) {
            auroraConfig.getSecrets(fields.extract("secretFolder"))
        } else null

        if (secrets != null && secrets.isEmpty()) {
            val error = Error(aid, listOf("No secret files with prefix ${fields.extract("secretFolder")}"))
            throw AuroraConfigException("Missing secret files",
                                        errors = listOf(error))
        }

        //here we need to handle more validation rule
        //if there are no secrets
        val type = fields.extract("type", { TemplateType.valueOf(it.textValue()) })

        if (type == TemplateType.process) throw  IllegalArgumentException("Not handled yet")

        val errors = extractors.mapNotNull { e ->
            e.validator(fields[e.name]?.value)
        }.toMutableList()

        //Everything below here is for every version of the schema.
        val artifactId = fields.extract("artifactId")
        val name = if (fields.containsKey("name")) {
            fields.extract("name")
        } else {
            artifactId
        }

        if (!Regex("^[a-z][-a-z0-9]{0,23}[a-z0-9]$").matches(name)) {
            errors + IllegalArgumentException("Name is not valid DNS952 label. 24 length alphanumeric.")
        }

        errors.takeIf { it.isNotEmpty() }?.let {
            throw ApplicationConfigException("Config for application ${aid.applicationName} in environment ${aid.environmentName} contains errors", errors = it.mapNotNull { it.message })
        }
        val groupId = fields.extract("groupId")


        val generatedCert = groupId + "." + name

        val webseal = if (!fields.containsKey("webseal/path")) {
            null
        } else {
            Webseal(
                    fields.extract("webseal/path"),
                    fields["webseal/roles"]?.value?.textValue()
            )
        }

        val prometheus = if (!fields.containsKey("prometheus/path")) {
            null
        } else {
            HttpEndpoint(
                    fields.extract("prometheus/path"),
                    fields["prometheus/port"]?.value?.intValue()
            )
        }

        val envName = if (fields.containsKey("envName")) {
            fields["envName"]!!.value.textValue()
        } else {
            aid.environmentName
        }

        val configMap: MutableMap<String, MutableMap<String, String>> = mutableMapOf()

        configExtractors.forEach {
            val (_, configFile, field) = it.name.split("/")

            val value = fields.extract(it.name)
            val keyValue = mutableMapOf(field to value)

            if (configMap.containsKey(configFile)) configMap[configFile]?.putAll(keyValue)
            else configMap.put(configFile, keyValue)
        }

        val auroraDeploymentConfig = AuroraDeploymentConfig(
                schemaVersion = fields.extract("schemaVersion"),
                affiliation = fields.extract("affiliation"),
                cluster = fields.extract("cluster"),
                type = type,
                name = name,
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
                envName = envName,
                permissions = Permissions(Permission(
                        fields.extract("permissions/admin/groups", { it.textValue().split(" ").toSet() }),
                        fields.extract("permissions/admin/users", { it.textValue().split(" ").toSet() })

                )),
                replicas = fields.extract("replicas").toInt(),
                artifactId = artifactId,
                groupId = groupId,
                version = fields.extract("version"),
                extraTags = fields.extract("extraTags"),
                splunkIndex = fields["splunkIndex"]?.value?.textValue(),
                database = fields["database"]?.value?.textValue(),
                certificateCn = fields["certificateCn"]?.value?.textValue() ?: generatedCert,
                webseal = webseal,
                prometheus = prometheus,
                managementPath = fields["managementPath"]?.value?.textValue(),
                secrets = secrets,
                config = if (configMap.isNotEmpty()) configMap else null
        )

        return auroraDeploymentConfig.apply {
            if (validateOpenShiftReferences) validateOpenShiftReferences(this)
        }
    }

    /**
     * Validates that references to objects on OpenShift in the configuration are valid.
     *
     * This method should probably be extracted into its own class at some point when we add more validation,
     * like references to templates, etc.
     */
    fun validateOpenShiftReferences(auroraDc: AuroraDeploymentConfig) {
        val errors: MutableList<String> = mutableListOf()

        auroraDc.permissions.admin.groups
                .filter { !openShiftClient.isValidGroup(it) }
                .takeIf { it.isNotEmpty() }
                ?.let { errors.add("The following groups are not valid=${it.joinToString()}") }

        auroraDc.permissions.admin.users
                .filter { !openShiftClient.isValidUser(it) }
                .takeIf { it.isNotEmpty() }
                ?.let { errors.add("The following users are not valid=${it.joinToString()}") }

        if (errors.isNotEmpty()) {
            throw ApplicationConfigException("Configuration contained references to one or more objects on OpenShift that does not exist", errors = errors)
        }
    }
}


