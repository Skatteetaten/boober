package no.skatteetaten.aurora.boober.facade

import org.springframework.stereotype.Service
import com.fasterxml.jackson.annotation.JsonInclude
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.feature.applicationDeploymentRef
import no.skatteetaten.aurora.boober.feature.cluster
import no.skatteetaten.aurora.boober.feature.envAutoDeploy
import no.skatteetaten.aurora.boober.feature.envName
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.toAdr
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.AuroraDeploymentContextService
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
import no.skatteetaten.aurora.boober.utils.parallelMap

private val logger = KotlinLogging.logger {}

@Service
class AuroraConfigFacade(
    private val auroraConfigService: AuroraConfigService,
    private val auroraDeploymentContextService: AuroraDeploymentContextService

) {

    fun findAuroraDeploymentSpec(
        ref: AuroraConfigRef,
        adrList: List<ApplicationDeploymentRef>
    ): List<AuroraDeploymentSpec> {
        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        return adrList.map {
            auroraDeploymentContextService.findApplicationDeploymentSpec(AuroraContextCommand(auroraConfig, it, ref))
        }
    }

    fun findAuroraDeploymentSpecForEnvironment(
        ref: AuroraConfigRef,
        environment: String,
        errorsAsWarnings: Boolean = false
    ): List<AuroraDeploymentSpec> {
        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        return auroraConfig.getApplicationDeploymentRefs()
            .filter { it.environment == environment }
            .map {
                auroraDeploymentContextService.findApplicationDeploymentSpec(
                    AuroraContextCommand(auroraConfig, it, ref, emptyList(), errorsAsWarnings)
                )
            }
    }

    fun findAuroraDeploymentSpecSingle(
        ref: AuroraConfigRef,
        adr: ApplicationDeploymentRef,
        overrideFiles: List<AuroraConfigFile>,
        errorsAsWarnings: Boolean
    ): AuroraDeploymentSpec {

        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        val cmd = AuroraContextCommand(
            auroraConfig = auroraConfig,
            applicationDeploymentRef = adr,
            auroraConfigRef = ref,
            overrides = overrideFiles,
            errorsAsWarnings = errorsAsWarnings
        )
        return auroraDeploymentContextService.findApplicationDeploymentSpec(cmd)
    }

    fun findAuroraConfigFilesForApplicationDeployment(
        ref: AuroraConfigRef,
        adr: ApplicationDeploymentRef
    ): List<AuroraConfigFile> {
        return auroraConfigService.findAuroraConfig(ref).getFilesForApplication(adr)
    }

    fun findAuroraConfig(ref: AuroraConfigRef): AuroraConfig {
        return auroraConfigService.findAuroraConfig(ref)
    }

    fun findAuroraConfigFileNames(ref: AuroraConfigRef): List<String> {
        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        return auroraConfig.files.map { it.name }
    }

    // Returns a map of ADR -> Warnings if that adr has warnings. In case of errors an exception is thrown
    fun validateAuroraConfig(
        localAuroraConfig: AuroraConfig,
        resourceValidation: Boolean = true,
        auroraConfigRef: AuroraConfigRef,
        mergeWithRemoteConfig: Boolean = false
    ): Map<ApplicationDeploymentRef, List<String>> {

        val auroraConfig = if (mergeWithRemoteConfig) {
            val remoteAuroraConfig = auroraConfigService.findAuroraConfig(auroraConfigRef)
            remoteAuroraConfig.merge(localAuroraConfig)
        } else {
            localAuroraConfig
        }

        val commands = auroraConfig.getApplicationDeploymentRefs().map {
            // this is to validate that there are no dangling files
            auroraConfig.getFilesForApplication(it)
            AuroraContextCommand(auroraConfig, it, auroraConfigRef, emptyList())
        }

        val (valid, invalid) = auroraDeploymentContextService.createValidatedAuroraDeploymentContexts(
            commands,
            resourceValidation
        )

        if (invalid.isNotEmpty()) {
            throw MultiApplicationValidationException(invalid.map { it.errors })
        }

        return valid.filter { it.warnings.isNotEmpty() }
            .associate {
                it.cmd.applicationDeploymentRef to it.warnings
            }
    }

    fun findAuroraConfigFile(ref: AuroraConfigRef, fileName: String): AuroraConfigFile {

        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        return auroraConfig.files.find { it.name == fileName }
            ?: throw IllegalArgumentException("No such file $fileName in AuroraConfig ${ref.name}")
    }

    fun updateAuroraConfigFile(
        ref: AuroraConfigRef,
        fileName: String,
        contents: String,
        previousVersion: String? = null
    ): AuroraConfigFile {

        val oldAuroraConfig = auroraConfigService.findAuroraConfig(ref)
        val (newFile, auroraConfig) = oldAuroraConfig.updateFile(fileName, contents, previousVersion)

        auroraConfigService.saveFile(newFile, auroraConfig, ref)
        return newFile
    }

    fun patchAuroraConfigFile(
        ref: AuroraConfigRef,
        filename: String,
        jsonPatchOp: String
    ): AuroraConfigFile {

        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        val (newFile, updatedAuroraConfig) = auroraConfig.patchFile(filename, jsonPatchOp)
        auroraConfigService.saveFile(newFile, updatedAuroraConfig, ref)

        return newFile
    }

    fun findAllAuroraConfigNames() = auroraConfigService.findAllAuroraConfigNames()

    fun searchForApplications(refName: String, environment: String): List<ApplicationSearchResult> {
        return this.findAllAuroraConfigNames().parallelMap { aff ->
            try {
                val ref = AuroraConfigRef(aff, refName)
                this.findAuroraDeploymentSpecForEnvironment(ref, environment)
                    .filter {
                        it.cluster == "utv"
                    }
                    .map {
                        val applicationDeploymentRef = it.applicationDeploymentRef.toAdr()

                        ApplicationSearchResult(
                            affiliation = aff,
                            autoDeploy = it.envAutoDeploy,
                            applicationDeploymentRef = applicationDeploymentRef,
                            warningMessage = if (applicationDeploymentRef.environment != it.envName) {
                                "Environment name is overwritten in config file. " +
                                    "ApplicationDeploymentRef.environment: ${applicationDeploymentRef.environment}, " +
                                    "AuroraDeploymentSpec.envName: ${it.envName}"
                            } else {
                                null
                            }
                        )
                    }
            } catch (e: Exception) {
                logger.info(e.message)
                listOf(ApplicationSearchResult(affiliation = aff, errorMessage = e.message))
            }
        }.flatten()
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApplicationSearchResult(
    val affiliation: String,
    val autoDeploy: Boolean? = null,
    val applicationDeploymentRef: ApplicationDeploymentRef? = null,
    val errorMessage: String? = null,
    val warningMessage: String? = null
)
