package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
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

class AuroraConfigWithOverrides(
    var auroraConfig: AuroraConfig,
    val overrideFiles: List<AuroraConfigFile> = listOf()
)

data class AuroraConfigRef(
    val name: String,
    val refName: String,
    val resolvedRef: String? = null
)

@Service
class AuroraConfigService(
    @TargetDomain(AURORA_CONFIG) val gitService: GitService,
    val bitbucketProjectService: BitbucketService,
    val auroraDeploymentContextService: AuroraDeploymentContextService,
    @Value("\${openshift.cluster}") val cluster: String,
    @Value("\${integrations.aurora.config.git.project}") val project: String
) {

    val logger: Logger = getLogger(AuroraConfigService::class.java)
    val mapper = jacksonObjectMapper()

    fun findAllAuroraConfigNames(): List<String> {

        return bitbucketProjectService.getRepoNames(project)
    }

    fun resolveToExactRef(ref: AuroraConfigRef): AuroraConfigRef {

        val exactRef = updateLocalFilesFromGit(ref)
        return exactRef?.let { ref.copy(resolvedRef = it) } ?: ref
    }

    fun findAuroraConfigFilesForApplicationDeployment(
        ref: AuroraConfigRef,
        adr: ApplicationDeploymentRef
    ): List<AuroraConfigFile> {
        return findAuroraConfig(ref).getFilesForApplication(adr)
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

        return saveFile(newFile, auroraConfig, ref)
    }

    fun patchAuroraConfigFile(
        ref: AuroraConfigRef,
        filename: String,
        jsonPatchOp: String,
        previousVersion: String? = null
    ): AuroraConfig {

        val auroraConfig = findAuroraConfig(ref)
        val (newFile, updatedAuroraConfig) = auroraConfig.patchFile(filename, jsonPatchOp, previousVersion)

        return saveFile(newFile, updatedAuroraConfig, ref)
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

    private fun saveFile(newFile: AuroraConfigFile, auroraConfig: AuroraConfig, ref: AuroraConfigRef): AuroraConfig {

        val watch = StopWatch()
        watch.start("find affected adr")
        val affectedAid = auroraConfig.getApplicationDeploymentRefs().filter {
            val files = auroraConfig.getFilesForApplication(it)
            files.any { it.name == newFile.name }
        }

        watch.stop()
        watch.start("validate")
        logger.debug("Affected AID for file={} adr={}", newFile, affectedAid)
        // This will validate both AuroraConfig and External validation for the affected AID
        auroraDeploymentContextService.createValidatedAuroraDeploymentContexts(affectedAid.map {
            AuroraContextCommand(auroraConfig, it, ref)
        })
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

    fun validateAuroraConfig(
        auroraConfig: AuroraConfig,
        overrideFiles: List<AuroraConfigFile> = listOf(),
        resourceValidation: Boolean = true,
        auroraConfigRef: AuroraConfigRef
    ) {
        val commands = auroraConfig.getApplicationDeploymentRefs().map {
            AuroraContextCommand(auroraConfig, it, auroraConfigRef, overrideFiles)
        }
        auroraDeploymentContextService.createValidatedAuroraDeploymentContexts(commands, resourceValidation)
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
                "An unexpected error occurred when checking out AuroraConfig with name ${ref.name} (${e.message})",
                e
            )
        }
    }
}
