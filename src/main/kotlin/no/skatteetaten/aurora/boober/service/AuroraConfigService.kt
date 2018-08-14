package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.experimental.ThreadPoolDispatcher
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import kotlinx.coroutines.experimental.runBlocking
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
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

data class AuroraConfigRef(
    val name: String,
    val refName: String
)

@Service
class AuroraConfigService(
    @TargetDomain(AURORA_CONFIG) val gitService: GitService,
    val bitbucketProjectService: BitbucketProjectService,
    val deploymentSpecValidator: AuroraDeploymentSpecValidator,
    @Value("\${openshift.cluster}") val cluster: String,
    @Value("\${boober.validationPoolSize:6}") val validationPoolSize: Int
) {

    val logger: Logger = getLogger(AuroraConfigService::class.java)
    val mapper = jacksonObjectMapper()

    private val dispatcher: ThreadPoolDispatcher = newFixedThreadPoolContext(validationPoolSize, "validationPool")

    fun findAllAuroraConfigNames(): List<String> {

        return bitbucketProjectService.getAllSlugs()
    }

    fun findExactRef(ref: AuroraConfigRef): String? {

        return updateLocalFilesFromGit(ref)
    }
    fun findAuroraConfig(ref: AuroraConfigRef): AuroraConfig {

        updateLocalFilesFromGit(ref)
        return AuroraConfig.fromFolder("${gitService.checkoutPath}/${ref.name}", ref.refName)
    }

    fun findAuroraConfigFileNames(ref: AuroraConfigRef): List<String> {

        val auroraConfig = findAuroraConfig(ref)
        return auroraConfig.files.map { it.name }
    }

    fun findAuroraConfigFile(ref: AuroraConfigRef, fileName: String): AuroraConfigFile? {

        val auroraConfig = findAuroraConfig(ref)
        return auroraConfig.findFile(fileName)
            ?: throw IllegalArgumentException("No such file $fileName in AuroraConfig ${ref.name}")
    }

    @JvmOverloads
    fun updateAuroraConfigFile(
        ref: AuroraConfigRef,
        fileName: String,
        contents: String,
        previousVersion: String? = null
    ): AuroraConfig {

        val oldAuroraConfig = findAuroraConfig(ref)
        val (newFile, auroraConfig) = oldAuroraConfig.updateFile(fileName, contents, previousVersion)

        return saveFile(newFile, auroraConfig)
    }

    fun patchAuroraConfigFile(
        ref: AuroraConfigRef,
        filename: String,
        jsonPatchOp: String,
        previousVersion: String? = null
    ): AuroraConfig {

        val auroraConfig = findAuroraConfig(ref)
        val (newFile, updatedAuroraConfig) = auroraConfig.patchFile(filename, jsonPatchOp, previousVersion)

        return saveFile(newFile, updatedAuroraConfig)
    }

    fun save(auroraConfig: AuroraConfig): AuroraConfig {
        val checkoutDir = getAuroraConfigFolder(auroraConfig.name)

        val refName = "master"
        val ref = AuroraConfigRef(auroraConfig.name, refName)
        val repo = getUpdatedRepo(ref)
        val existing = AuroraConfig.fromFolder(checkoutDir, refName)

        existing.files.forEach {
            val outputFile = File(checkoutDir, it.name)
            FileUtils.deleteQuietly(outputFile)
        }
        auroraConfig.files.forEach {
            val outputFile = File(getAuroraConfigFolder(auroraConfig.name), it.name)
            FileUtils.forceMkdirParent(outputFile)
            outputFile.writeText(it.contents)
        }

        gitService.commitAndPushChanges(repo)
        repo.close()
        return auroraConfig
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
        // This will validate both AuroraConfig and External validation for the affected AID
        createValidatedAuroraDeploymentSpecs(AuroraConfigWithOverrides(auroraConfig), affectedAid)
        watch.stop()

        val checkoutDir = getAuroraConfigFolder(auroraConfig.name)
        watch.start("write file")

        val outputFile = File(checkoutDir, newFile.name)
        FileUtils.forceMkdirParent(outputFile)
        outputFile.writeText(newFile.contents)
        watch.stop()

        watch.start("git")
        val repo = Git.open(checkoutDir)
        gitService.commitAndPushChanges(repo)
        repo.close()
        watch.stop()

        logger.debug(watch.prettyPrint())
        return auroraConfig
    }

    @JvmOverloads
    fun createValidatedAuroraDeploymentSpecs(
        ref: AuroraConfigRef,
        applicationIds: List<ApplicationId>,
        overrideFiles: List<AuroraConfigFile> = listOf(),
        resourceValidation: Boolean = true
    ): List<AuroraDeploymentSpecInternal> {

        val auroraConfig = findAuroraConfig(ref)
        return createValidatedAuroraDeploymentSpecs(
            AuroraConfigWithOverrides(auroraConfig, overrideFiles),
            applicationIds
        )
    }

    fun validateAuroraConfig(
        auroraConfig: AuroraConfig,
        overrideFiles: List<AuroraConfigFile> = listOf(),
        resourceValidation: Boolean = true
    ) {
        val applicationIds = auroraConfig.getApplicationIds()
        createValidatedAuroraDeploymentSpecs(
            AuroraConfigWithOverrides(auroraConfig, overrideFiles),
            applicationIds,
            resourceValidation
        )
    }

    private fun getAuroraConfigFolder(name: String) = File(gitService.checkoutPath, name)

    private fun updateLocalFilesFromGit(ref: AuroraConfigRef): String? {
        val repository = getUpdatedRepo(ref)

        val head = repository.repository.exactRef("HEAD").objectId?.abbreviate(8)?.name()

        repository.close()
        return head
    }

    private fun getUpdatedRepo(ref: AuroraConfigRef): Git {
        return try {
            gitService.checkoutRepository(ref.name, refName = ref.refName)
        } catch (e: InvalidRemoteException) {
            throw IllegalArgumentException("No such AuroraConfig ${ref.name}")
        } catch (e: Exception) {
            throw AuroraConfigServiceException(
                "An unexpected error occurred when checking out AuroraConfig with name ${ref.name}",
                e
            )
        }
    }

    private fun createValidatedAuroraDeploymentSpecs(
        auroraConfigWithOverrides: AuroraConfigWithOverrides,
        applicationIds: List<ApplicationId>,
        resourceValidation: Boolean = true
    ): List<AuroraDeploymentSpecInternal> {

        val stopWatch = StopWatch().apply { start() }
        val specInternals: List<AuroraDeploymentSpecInternal> = runBlocking(dispatcher) {
            applicationIds.map { aid ->
                async(dispatcher) {
                    try {
                        val spec =
                            createValidatedAuroraDeploymentSpec(auroraConfigWithOverrides, aid, resourceValidation)
                        Pair<AuroraDeploymentSpecInternal?, ExceptionWrapper?>(first = spec, second = null)
                    } catch (e: Throwable) {
                        Pair<AuroraDeploymentSpecInternal?, ExceptionWrapper?>(first = null, second = ExceptionWrapper(aid, e))
                    }
                }
            }
                .map { it.await() }
        }.onErrorThrow(::MultiApplicationValidationException)
        stopWatch.stop()
        logger.debug("Validated AuroraConfig ${auroraConfigWithOverrides.auroraConfig.name} with ${applicationIds.size} applications in ${stopWatch.totalTimeMillis} millis")
        return specInternals
    }

    private fun createValidatedAuroraDeploymentSpec(
        auroraConfigWithOverrides: AuroraConfigWithOverrides,
        aid: ApplicationId,
        resourceValidation: Boolean = true
    ): AuroraDeploymentSpecInternal {

        val stopWatch = StopWatch().apply { start() }
        val spec = AuroraDeploymentSpecService.createAuroraDeploymentSpecInternal(
            auroraConfigWithOverrides.auroraConfig,
            aid,
            auroraConfigWithOverrides.overrideFiles
        )
        if (spec.cluster == cluster && resourceValidation) {
            deploymentSpecValidator.assertIsValid(spec)
        }
        stopWatch.stop()

        logger.debug("Created ADC for app=${aid.application}, env=${aid.environment} in ${stopWatch.totalTimeMillis} millis")

        return spec
    }
}