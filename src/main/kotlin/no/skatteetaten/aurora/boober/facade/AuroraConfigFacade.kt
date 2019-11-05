package no.skatteetaten.aurora.boober.facade

import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.AuroraDeploymentContextService
import org.springframework.stereotype.Service

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
            auroraDeploymentContextService.createAuroraDeploymentContext(AuroraContextCommand(auroraConfig, it, ref))
                .spec
        }
    }

    fun findAuroraDeploymentSpecForEnvironment(
        ref: AuroraConfigRef,
        environment: String
    ): List<AuroraDeploymentSpec> {
        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        return auroraConfig.getApplicationDeploymentRefs()
            .filter { it.environment == environment }
            .map {
                auroraDeploymentContextService.createAuroraDeploymentContext(
                    AuroraContextCommand(auroraConfig, it, ref)
                ).spec
            }
    }

    fun findAuroraDeploymentSpecSingle(
        ref: AuroraConfigRef,
        adr: ApplicationDeploymentRef,
        overrideFiles: List<AuroraConfigFile>
    ): AuroraDeploymentSpec {

        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        val cmd = AuroraContextCommand(
            auroraConfig = auroraConfig,
            applicationDeploymentRef = adr,
            auroraConfigRef = ref,
            overrides = overrideFiles
        )
        return auroraDeploymentContextService.createAuroraDeploymentContext(cmd).spec
    }

    fun findAuroraConfigFilesForApplicationDeployment(
        ref: AuroraConfigRef,
        adr: ApplicationDeploymentRef
    ): List<AuroraConfigFile> {
        return auroraConfigService.findAuroraConfig(ref).getFilesForApplication(adr)
    }

    fun findAuroraConfigFiles(ref: AuroraConfigRef): List<AuroraConfigFile> {
        return auroraConfigService.findAuroraConfig(ref).files
    }

    fun findAuroraConfigFileNames(ref: AuroraConfigRef): List<String> {
        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        return auroraConfig.files.map { it.name }
    }

    fun validateAuroraConfig(
        auroraConfig: AuroraConfig,
        overrideFiles: List<AuroraConfigFile> = listOf(),
        resourceValidation: Boolean = true,
        auroraConfigRef: AuroraConfigRef
    ): List<AuroraDeploymentContext> {
        val commands = auroraConfig.getApplicationDeploymentRefs().map {
            AuroraContextCommand(auroraConfig, it, auroraConfigRef, overrideFiles)
        }
        return auroraDeploymentContextService.createValidatedAuroraDeploymentContexts(commands, resourceValidation)
    }

    fun findAuroraConfigFile(ref: AuroraConfigRef, fileName: String): AuroraConfigFile {

        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        return auroraConfig.findFile(fileName)
            ?: throw IllegalArgumentException("No such file $fileName in AuroraConfig ${ref.name}")
    }

    fun updateAuroraConfigFile(
        ref: AuroraConfigRef,
        fileName: String,
        contents: String,
        previousVersion: String? = null
    ): AuroraConfig {

        val oldAuroraConfig = auroraConfigService.findAuroraConfig(ref)
        val (newFile, auroraConfig) = oldAuroraConfig.updateFile(fileName, contents, previousVersion)

        return auroraConfigService.saveFile(newFile, auroraConfig, ref)
    }

    fun patchAuroraConfigFile(
        ref: AuroraConfigRef,
        filename: String,
        jsonPatchOp: String,
        previousVersion: String? = null
    ): AuroraConfig {

        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        val (newFile, updatedAuroraConfig) = auroraConfig.patchFile(filename, jsonPatchOp, previousVersion)

        return auroraConfigService.saveFile(newFile, updatedAuroraConfig, ref)
    }

    fun findAllAuroraConfigNames() = auroraConfigService.findAllAuroraConfigNames()
}
