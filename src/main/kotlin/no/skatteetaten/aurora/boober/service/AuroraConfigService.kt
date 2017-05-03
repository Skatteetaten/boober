package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.utils.Result
import no.skatteetaten.aurora.boober.utils.extract
import no.skatteetaten.aurora.boober.utils.findConfigExtractors
import no.skatteetaten.aurora.boober.utils.orElseThrow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import kotlin.system.measureTimeMillis

@Service
class AuroraConfigService(
        val gitService: GitService,
        val openShiftClient: OpenShiftClient,
        val mapper: ObjectMapper,
        val encryptionService: EncryptionService) {

    private val SECRET_FOLDER = ".secret"
    private val logger = LoggerFactory.getLogger(AuroraConfigService::class.java)

    fun save(affiliation: String, auroraConfig: AuroraConfig) {

        validate(auroraConfig)
        val jsonFiles: Map<String, String> = auroraConfig.auroraConfigFiles.map {
            it.name to mapper.writerWithDefaultPrettyPrinter().writeValueAsString(it.contents)
        }.toMap()

        val encryptedSecrets = auroraConfig.secrets.map {
            "$SECRET_FOLDER/${it.key}" to encryptionService.encrypt(it.value)
        }.toMap()

        gitService.saveFilesAndClose(affiliation, jsonFiles + encryptedSecrets)
    }

    fun findAuroraConfig(affiliation: String): AuroraConfig {

        return withAuroraConfig(affiliation, false, function = { it })
    }

    fun createAuroraConfigFromFiles(filesForAffiliation: Map<String, File>): AuroraConfig {

        val secretFiles: Map<String, String> = filesForAffiliation
                .filter { it.key.startsWith(SECRET_FOLDER) }
                .map { it.key.removePrefix("$SECRET_FOLDER/") to encryptionService.decrypt(it.value.readText()) }.toMap()

        val auroraConfigFiles = filesForAffiliation
                .filter { !it.key.startsWith(SECRET_FOLDER) }
                .map { AuroraConfigFile(it.key, mapper.readValue(it.value)) }

        return AuroraConfig(auroraConfigFiles = auroraConfigFiles, secrets = secretFiles)
    }

    fun withAuroraConfig(affiliation: String,
                         commitChanges: Boolean = true,
                         function: (AuroraConfig) -> AuroraConfig = { it -> it }): AuroraConfig {

        val startCheckout = System.currentTimeMillis()
        val repo = gitService.checkoutRepoForAffiliation(affiliation)
        logger.debug("Spent {} millis checking out gir repository", System.currentTimeMillis() - startCheckout)

        val filesForAffiliation: Map<String, File> = gitService.getAllFilesInRepo(repo)
        val auroraConfig = createAuroraConfigFromFiles(filesForAffiliation)

        val newAuroraConfig = function(auroraConfig)

        if (commitChanges) {
            measureTimeMillis {
                save(affiliation, newAuroraConfig)
            }.let { logger.debug("Spent {} millis committing and pushing to git", it) }
        } else {
            measureTimeMillis {
                gitService.closeRepository(repo)
            }.let { logger.debug("Spent {} millis closing git repository", it) }
        }

        return newAuroraConfig
    }

    fun createAuroraDcs(auroraConfig: AuroraConfig,
                        applicationIds: List<ApplicationId>,
                        overrides: List<AuroraConfigFile> = listOf(),
                        validateOpenShiftReferences: Boolean = true): List<AuroraDeploymentConfig> {

        return applicationIds.map { aid ->
            val result: Result<AuroraDeploymentConfig?, Error?> = try {
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

        //here we need to handle more validation rule
        //if there are no secrets
        val type = fields.extract("type", { TemplateType.valueOf(it.textValue()) })

        if (type == TemplateType.process) throw  IllegalArgumentException("Not handled yet")

        val errors = extractors.mapNotNull { e ->
            e.validator(fields[e.path]?.value)
        }.toMutableList()

        //Everything below here is for every version of the schema.
        val artifactId = fields.extract("artifactId")
        val name = if (fields.containsKey("name")) {
            fields.extract("/name")
        } else {
            artifactId
        }

        if (!Regex("^[a-z][-a-z0-9]{0,23}[a-z0-9]$").matches(name)) {
            errors + IllegalArgumentException("Name is not valid DNS952 label. 24 length alphanumeric.")
        }

        errors.takeIf { it.isNotEmpty() }?.let {
            throw ApplicationConfigException("Config for application ${aid.applicationName} in environment ${aid.environmentName} contains errors", errors = it.mapNotNull { it.message })
        }


        val generatedCert = "" //TODO:fix

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

        val auroraDeploymentConfig = AuroraDeploymentConfig(
                schemaVersion = fields.extract("schemaVersion"),
                affiliation = fields.extract("affiliation"),
                cluster = fields.extract("cluster"),
                type = type,
                name = name,
                flags = AuroraDeploymentConfigFlags(
                        fields.extract("flags/route", { it.booleanValue() }),
                        fields.extract("flags/cert", { it.booleanValue() }),
                        fields.extract("flags/debug", { it.booleanValue() }),
                        fields.extract("flags/alarm", { it.booleanValue() }),
                        fields.extract("flags/rolling", { it.booleanValue() })
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
                envName = fields["envName"]?.value?.textValue() ?: aid.environmentName,
                permissions = Permissions(Permission(
                        fields.extract("permissions/admin/groups", { it.textValue().split(",").toSet() }),
                        fields.extract("permissions/admin/users", { it.textValue().split(",").toSet() })

                )),
                secrets = mapOf(), //TODO handle,
                config = mapOf(), //TODO handle,
                replicas = fields["replicas"]?.value?.intValue() ?: 1,
                artifactId = artifactId,
                groupId = fields.extract("groupId"),
                version = fields.extract("version"),
                extraTags = fields.extract("extraTags"),
                splunkIndex = fields["splunkIndex"]?.value?.textValue(),
                database = fields["database"]?.value?.textValue(),
                certificateCn = fields["certificateCn"]?.value?.textValue() ?: generatedCert,
                webseal = webseal,
                prometheus = prometheus,
                managementPath = fields.extract("managmenetPath")
        )


        return auroraDeploymentConfig.apply { if (validateOpenShiftReferences) validateOpenShiftReferences(this) }
    }




    /**
     * Validates that references to objects on OpenShift in the configuration are valid.
     *
     * This method should probably be extracted into its own class at some point when we add more validation,
     * like references to templates, etc.
     */
    private fun validateOpenShiftReferences(auroraDc: AuroraDeploymentConfig) {
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


    private fun validate(auroraConfig: AuroraConfig) {
        val appIds = auroraConfig.getApplicationIds()
        // Verify that all AuroraDeploymentConfigs represented by the AuroraConfig are valid
        createAuroraDcs(auroraConfig, appIds)
    }
}


fun Map<String, Any?>.s(field: String) = this[field]?.toString()
fun Map<String, Any?>.i(field: String) = this[field] as Int?
fun Map<String, Any?>.m(field: String) = this[field] as Map<String, Any?>?
fun Map<String, Any?>.b(field: String) = this[field] as Boolean?
fun Map<String, Any?>.a(field: String) = this[field] as List<String>?
