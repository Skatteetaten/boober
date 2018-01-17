package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.experimental.ThreadPoolDispatcher
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import kotlinx.coroutines.experimental.runBlocking
import no.skatteetaten.aurora.boober.mapper.v1.createAuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.GitServices.Domain.AURORA_CONFIG
import no.skatteetaten.aurora.boober.service.GitServices.TargetDomain
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.InvalidRemoteException
import org.slf4j.Logger
import org.slf4j.LoggerFactory.getLogger
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.util.StopWatch
import java.io.File

class AuroraConfigWithOverrides(
        var auroraConfig: AuroraConfig,
        val overrideFiles: List<AuroraConfigFile> = listOf()
)

@Service
class AuroraConfigService(@TargetDomain(AURORA_CONFIG) val gitService: GitService,
                          val bitbucketProjectService: BitbucketProjectService,
                          val deploymentSpecValidator: AuroraDeploymentSpecValidator,
                          @Value("\${openshift.cluster}") val cluster: String,
                          @Value("\${boober.validationPoolSize:6}") val validationPoolSize: Int) {


    val logger: Logger = getLogger(AuroraConfigService::class.java)

    private val dispatcher: ThreadPoolDispatcher = newFixedThreadPoolContext(validationPoolSize, "validationPool")

    fun findAllAuroraConfigNames(): List<String> {

        return bitbucketProjectService.getAllSlugs()
    }

    fun findAuroraConfig(name: String): AuroraConfig {

        updateLocalFilesFromGit(name)
        return AuroraConfig.fromFolder("${gitService.checkoutPath}/$name")
    }

    fun findAuroraConfigFileNames(name: String): List<String> {

        val auroraConfig = findAuroraConfig(name)
        return auroraConfig.auroraConfigFiles.map { it.name }
    }

    fun findAuroraConfigFile(name: String, fileName: String): AuroraConfigFile? {

        val auroraConfig = findAuroraConfig(name)
        return auroraConfig.findFile(fileName)
                ?: throw IllegalArgumentException("No such file $fileName in AuroraConfig $name")
    }

    fun save(auroraConfig: AuroraConfig): AuroraConfig {
        auroraConfig.validate()

        val mapper = jacksonObjectMapper()
        val checkoutDir = getAuroraConfigFolder(auroraConfig.affiliation)

        val repo = getUpdatedRepo(auroraConfig.affiliation)
        val existing = AuroraConfig.fromFolder(checkoutDir)

        existing.auroraConfigFiles.forEach {
            val outputFile = File(checkoutDir, it.name)
            FileUtils.deleteQuietly(outputFile)
        }
        auroraConfig.auroraConfigFiles.forEach {
            val prettyContent = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(it.contents)
            val outputFile = File(getAuroraConfigFolder(auroraConfig.affiliation), it.name)
            FileUtils.forceMkdirParent(outputFile)
            outputFile.writeText(prettyContent)
        }

        gitService.commitAndPushChanges(repo)
        repo.close()
        return auroraConfig
    }

    fun updateAuroraConfigFile(name: String, fileName: String, contents: String, previousVersion: String? = null): AuroraConfig {

        val jsonContents = jacksonObjectMapper().readValue(contents, JsonNode::class.java)
        val oldAuroraConfig = findAuroraConfig(name)
        val (newFile, auroraConfig) = oldAuroraConfig.updateFile(fileName, jsonContents, previousVersion)

        return saveFile(newFile, auroraConfig)
    }

    fun patchAuroraConfigFile(name: String, filename: String, jsonPatchOp: String, previousVersion: String? = null): AuroraConfig {

        val auroraConfig = findAuroraConfig(name)
        val (newFile, updatedAuroraConfig) = auroraConfig.patchFile(filename, jsonPatchOp, previousVersion)


        return saveFile(newFile, updatedAuroraConfig)
    }

    private fun saveFile(newFile: AuroraConfigFile, auroraConfig: AuroraConfig): AuroraConfig {

        val watch = StopWatch()
        watch.start("find affected aid")
        val affectedAid = auroraConfig.getApplicationIds().filter {
            val files = auroraConfig.getFilesForApplication(it)
            files.any { it.name == newFile.name }
        }

        watch.stop()
        watch.start("validate")
        logger.debug("Affected AID for file={} aid={}", newFile, affectedAid)
        //This will validate both AuororaConfig and External validation for the affected AID
        createValidatedAuroraDeploymentSpecs(AuroraConfigWithOverrides(auroraConfig), affectedAid)
        watch.stop()

        val mapper = jacksonObjectMapper()
        val checkoutDir = getAuroraConfigFolder(auroraConfig.affiliation)
        watch.start("write file")
        val prettyContent = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(newFile.contents)
        val outputFile = File(checkoutDir, newFile.name)
        FileUtils.forceMkdirParent(outputFile)
        outputFile.writeText(prettyContent)
        watch.stop()

        watch.start("git")
        val repo = Git.open(checkoutDir)
        gitService.commitAndPushChanges(repo)
        repo.close()
        watch.stop()

        logger.debug(watch.prettyPrint())
        return auroraConfig
    }

    private fun getAuroraConfigFolder(name: String) = File(gitService.checkoutPath, name)

    private fun updateLocalFilesFromGit(name: String) {
        val repository = getUpdatedRepo(name)
        repository.close()
    }

    private fun getUpdatedRepo(name: String): Git {
        return try {
            gitService.checkoutRepository(name)
        } catch (e: InvalidRemoteException) {
            throw IllegalArgumentException("No such AuroraConfig $name")
        } catch (e: Exception) {
            throw AuroraConfigServiceException("An unexpected error occurred when checking out AuroraConfig with name $name", e)
        }
    }

    fun createValidatedAuroraDeploymentSpecs(auroraConfigName: String, applicationIds: List<ApplicationId>, overrideFiles: List<AuroraConfigFile> = listOf()): List<AuroraDeploymentSpec> {

        val auroraConfig = findAuroraConfig(auroraConfigName)
        return createValidatedAuroraDeploymentSpecs(AuroraConfigWithOverrides(auroraConfig, overrideFiles), applicationIds)
    }

    fun validateAuroraConfig(auroraConfig: AuroraConfig, overrideFiles: List<AuroraConfigFile> = listOf()) {
        val applicationIds = auroraConfig.getApplicationIds()
        createValidatedAuroraDeploymentSpecs(AuroraConfigWithOverrides(auroraConfig, overrideFiles), applicationIds)
    }

    private fun createValidatedAuroraDeploymentSpecs(auroraConfigWithOverrides: AuroraConfigWithOverrides, applicationIds: List<ApplicationId>): List<AuroraDeploymentSpec> {

        val stopWatch = StopWatch().apply { start() }
        val specs: List<AuroraDeploymentSpec> = runBlocking(dispatcher) {
            applicationIds.map { aid ->
                async(dispatcher) {
                    try {
                        val spec = createValidatedAuroraDeploymentSpec(auroraConfigWithOverrides, aid)
                        Pair<AuroraDeploymentSpec?, ExceptionWrapper?>(first = spec, second = null)
                    } catch (e: Throwable) {
                        Pair<AuroraDeploymentSpec?, ExceptionWrapper?>(first = null, second = ExceptionWrapper(aid, e))
                    }
                }
            }
                    .map { it.await() }
        }.onErrorThrow(::MultiApplicationValidationException)
        stopWatch.stop()
        logger.debug("Validated AuroraConfig ${auroraConfigWithOverrides.auroraConfig.affiliation} with ${applicationIds.size} applications in ${stopWatch.totalTimeMillis} millis")
        return specs
    }

    private fun createValidatedAuroraDeploymentSpec(auroraConfigWithOverrides: AuroraConfigWithOverrides, aid: ApplicationId): AuroraDeploymentSpec {

        val stopWatch = StopWatch().apply { start() }
        val spec = createAuroraDeploymentSpec(auroraConfigWithOverrides.auroraConfig, aid, auroraConfigWithOverrides.overrideFiles)
        if (spec.cluster == cluster) {
            deploymentSpecValidator.assertIsValid(spec)
        }
        stopWatch.stop()

        logger.debug("Created ADC for app=${aid.application}, env=${aid.environment} in ${stopWatch.totalTimeMillis} millis")

        return spec
    }

}