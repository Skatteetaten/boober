package no.skatteetaten.aurora.boober.facade

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.fge.jsonpatch.JsonPatch
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.mapper.AuroraConfigValidator
import no.skatteetaten.aurora.boober.mapper.v1.*
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.internal.*
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.utils.required
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File

@Service
class DeployBundleService(
        @Value("\${boober.docker.registry}") val dockerRegistry: String,
        val openShiftClient: OpenShiftClient,
        val gitService: GitService,
        val mapper: ObjectMapper,
        val secretVaultFacade: VaultFacade) {

    private val GIT_SECRET_FOLDER = ".secret"
    private val logger = LoggerFactory.getLogger(DeployBundleService::class.java)


    fun createDeployBundle(affiliation: String, overrideFiles: List<AuroraConfigFile> = listOf()): DeployBundle {
        val repo = getRepo(affiliation)
        val allFilesInRepo: Map<String, Pair<RevCommit?, File>> = gitService.getAllFilesInRepo(repo)
        val auroraConfig = createAuroraConfigFromFiles(allFilesInRepo, affiliation)
        val vaults = secretVaultFacade.listVaults(affiliation, repo).associateBy { it.name }

        return DeployBundle(auroraConfig = auroraConfig, vaults = vaults, repo = repo, overrideFiles = overrideFiles)
    }

    fun <T> withDeployBundle(affiliation: String, overrideFiles: List<AuroraConfigFile> = listOf(), function: (DeployBundle) -> T): T {

        val deployBundle = createDeployBundle(affiliation, overrideFiles)
        val res = function(deployBundle)
        gitService.closeRepository(deployBundle.repo)
        return res
    }

    fun findAuroraConfig(affiliation: String): AuroraConfig {
        val repo = getRepo(affiliation)
        val allFilesInRepo: Map<String, Pair<RevCommit?, File>> = gitService.getAllFilesInRepo(repo)
        return createAuroraConfigFromFiles(allFilesInRepo, affiliation)
    }

    fun saveAuroraConfig(auroraConfig: AuroraConfig, validateVersions: Boolean): AuroraConfig {
        return withAuroraConfig(auroraConfig.affiliation, validateVersions, {
            auroraConfig
        })
    }

    fun patchAuroraConfigFile(affiliation: String, filename: String, jsonPatchOp: String, configFileVersion: String, validateVersions: Boolean): AuroraConfig {

        return withAuroraConfig(affiliation, validateVersions, { auroraConfig: AuroraConfig ->
            val patch: JsonPatch = mapper.readValue(jsonPatchOp, JsonPatch::class.java)

            val auroraConfigFile = auroraConfig.auroraConfigFiles.filter { it.name == filename }.first()
            val originalContentsNode = mapper.convertValue(auroraConfigFile.contents, JsonNode::class.java)

            val fileContents = patch.apply(originalContentsNode)
            auroraConfig.updateFile(filename, fileContents, configFileVersion)
        })
    }

    fun updateAuroraConfigFile(affiliation: String, filename: String, fileContents: JsonNode, configFileVersion: String, validateVersions: Boolean): AuroraConfig {
        return withAuroraConfig(affiliation, validateVersions, { auroraConfig: AuroraConfig ->
            auroraConfig.updateFile(filename, fileContents, configFileVersion)
        })
    }

    fun validate(deployBundle: DeployBundle) {

        tryCreateAuroraApplications(deployBundle, deployBundle.auroraConfig.getApplicationIds())
    }

    fun createAuroraApplications(deployBundle: DeployBundle, applicationIds: List<ApplicationId>): List<AuroraApplication> {

        return tryCreateAuroraApplications(deployBundle, applicationIds)
    }

    fun createAuroraApplication(deployBundle: DeployBundle, applicationId: ApplicationId): AuroraApplication {

        val baseHandlers = setOf(
                AuroraConfigFieldHandler("schemaVersion"),
                AuroraConfigFieldHandler("type", validator = { it.required("Type is required") }),
                AuroraConfigFieldHandler("baseFile"),
                AuroraConfigFieldHandler("envFile")
        )
        val auroraConfig = deployBundle.auroraConfig
        val overrideFiles = deployBundle.overrideFiles
        val vaults = deployBundle.vaults
        val applicationFiles = auroraConfig.getFilesForApplication(applicationId, overrideFiles)
        val fields = AuroraConfigFields.create(baseHandlers, applicationFiles)

        val type = fields.extract("type", { TemplateType.valueOf(it.textValue()) })

        val schemaVersion = fields.extract("schemaVersion")

        if (schemaVersion != "v1") {
            throw IllegalArgumentException("Only v1 of schema is supported")
        }
        val applicationMapper = AuroraApplicationMapperV1(openShiftClient, applicationId)
        val deployMapper = AuroraDeployMapperV1(applicationId, applicationFiles, deployBundle.overrideFiles, dockerRegistry)
        val volumeMapper = AuroraVolumeMapperV1(applicationFiles, vaults)
        val routeMapper = AuroraRouteMapperV1(applicationFiles)
        val localTemplateMapper = AuroraLocalTemplateMapperV1(applicationFiles, auroraConfig)
        val templateMapper = AuroraTemplateMapperV1(applicationFiles, openShiftClient)
        val buildMapper = AuroraBuildMapperV1()
        val handlers = (baseHandlers + applicationMapper.handlers + when (type) {
            TemplateType.deploy -> routeMapper.handlers + volumeMapper.handlers + deployMapper.handlers
            TemplateType.development -> routeMapper.handlers + volumeMapper.handlers + deployMapper.handlers + buildMapper.handlers
            TemplateType.localTemplate -> routeMapper.handlers + volumeMapper.handlers + localTemplateMapper.handlers
            TemplateType.template -> routeMapper.handlers + volumeMapper.handlers + templateMapper.handlers
            TemplateType.build -> buildMapper.handlers
        }).toSet()

        val auroraConfigFields = AuroraConfigFields.create(handlers, applicationFiles)
        val validator = AuroraConfigValidator(applicationId, applicationFiles, handlers, auroraConfigFields)
        validator.validate()


        val volume = if (type == TemplateType.build) null else volumeMapper.auroraDeploymentCore(auroraConfigFields)
        val route = if (type == TemplateType.build) null else routeMapper.route(auroraConfigFields)

        val build = if (type == TemplateType.build || type == TemplateType.development) buildMapper.build(auroraConfigFields, dockerRegistry) else null

        val deploy = if (type == TemplateType.deploy || type == TemplateType.development) deployMapper.deploy(auroraConfigFields) else null

        val template = if (type == TemplateType.template) templateMapper.template(auroraConfigFields) else null

        val localTemplate = if (type == TemplateType.localTemplate) localTemplateMapper.localTemplate(auroraConfigFields) else null

        return applicationMapper.auroraApplicationConfig(auroraConfigFields, volume, route, build, deploy, template, localTemplate)
    }

    private fun tryCreateAuroraApplications(deployBundle: DeployBundle, applicationIds: List<ApplicationId>): List<AuroraApplication> {

        return applicationIds.map { aid ->
            try {
                val value = createAuroraApplication(deployBundle, aid)
                Result<AuroraApplication, Error?>(value = value)
            } catch (e: ApplicationConfigException) {
                logger.debug("ACE {}", e.errors)
                Result<AuroraApplication, Error?>(error = Error(aid.application, aid.environment, e.errors))
            } catch (e: IllegalArgumentException) {
                logger.debug("IAE {}", e.message)
                Result<AuroraApplication, Error?>(error = Error(aid.application, aid.environment, listOf(ValidationError(e.message!!))))
            }
        }.onErrorThrow {
            logger.debug("ACE {}", it)
            AuroraConfigException("AuroraConfig contained errors for one or more applications", it)
        }
    }

    private fun withAuroraConfig(affiliation: String,
                                 validateVersions: Boolean,
                                 function: (AuroraConfig) -> AuroraConfig = { it -> it }): AuroraConfig {

        val deployBundle = createDeployBundle(affiliation)
        val auroraConfig = deployBundle.auroraConfig
        val repo = deployBundle.repo

        val newAuroraConfig = function(auroraConfig)
        deployBundle.auroraConfig = auroraConfig

        if (validateVersions) {
            validateGitVersion(auroraConfig, newAuroraConfig, gitService.getAllFilesInRepo(repo))
        }
        validate(deployBundle)
        commitAuroraConfig(repo, newAuroraConfig)

        return newAuroraConfig
    }

    private fun validateGitVersion(auroraConfig: AuroraConfig, newAuroraConfig: AuroraConfig, allFilesInRepo: Map<String, Pair<RevCommit?, File>>) {
        val oldVersions = auroraConfig.getVersions().filterValues { it != null }
        val invalidVersions = newAuroraConfig.getVersions().filter {
            oldVersions[it.key] != it.value
        }

        if (invalidVersions.isNotEmpty()) {
            val gitInfo: Map<String, RevCommit> = allFilesInRepo
                    .filter { it.value.first != null }
                    .mapValues { it.value.first!! }

            val errors = invalidVersions.map {
                val commit = gitInfo[it.key]!!
                VersioningError(it.key, commit.authorIdent.name, commit.authorIdent.`when`)
            }

            throw AuroraVersioningException("Source file has changed since you fetched it", errors)
        }
    }

    private fun getRepo(affiliation: String): Git {

        val startCheckout = System.currentTimeMillis()
        val repo = gitService.checkoutRepoForAffiliation(affiliation)
        logger.debug("Spent {} millis checking out gir repository", System.currentTimeMillis() - startCheckout)

        return repo
    }

    private fun createAuroraConfigFromFiles(filesFromGit: Map<String, Pair<RevCommit?, File>>, affiliation: String): AuroraConfig {

        val auroraConfigFiles = filesFromGit
                .filter { !it.key.startsWith(GIT_SECRET_FOLDER) }
                .map { AuroraConfigFile(it.key, mapper.readValue(it.value.second), version = it.value.first?.abbreviate(7)?.name()) }

        return AuroraConfig(auroraConfigFiles = auroraConfigFiles, affiliation = affiliation)
    }


    private fun commitAuroraConfig(repo: Git,
                                   newAuroraConfig: AuroraConfig) {

        val configFiles: Map<String, String> = newAuroraConfig.auroraConfigFiles.map {
            it.name to mapper.writerWithDefaultPrettyPrinter().writeValueAsString(it.contents)
        }.toMap()


        //when we save auroraConfig we do not want to mess with secret vault files
        val keep: (String) -> Boolean = { it -> !it.startsWith(GIT_SECRET_FOLDER) }
        gitService.saveFilesAndClose(repo, configFiles, keep)
    }
}
