package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.GitServices.Domain.AURORA_CONFIG
import no.skatteetaten.aurora.boober.service.GitServices.TargetDomain
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.InvalidRemoteException
import org.springframework.stereotype.Service
import java.io.File

@Service
class AuroraConfigService(@TargetDomain(AURORA_CONFIG) val gitService: GitService) {

    fun findAuroraConfig(name: String): AuroraConfig {

        updateLocalFilesFromGit(name)
        return AuroraConfig.fromFolder("${gitService.checkoutPath}/$name")
    }

    fun findAuroraConfigFileNames(name: String): List<String> {

        val auroraConfig = findAuroraConfig(name)
        return auroraConfig.auroraConfigFiles.map { it.name }
    }

    fun findAuroraConfigFile(name: String, fileName: String): AuroraConfigFile? {

        updateLocalFilesFromGit(name)
        val file = File(gitService.checkoutPath, fileName)
        return if (file.exists()) AuroraConfigFile(file.path, jacksonObjectMapper().readValue(file))
        else throw IllegalArgumentException("No such file $fileName in AuroraConfig $name")
    }


    private fun updateLocalFilesFromGit(name: String): Git {
        return try {
            gitService.checkoutRepository(name)
        } catch (e: InvalidRemoteException) {
            throw IllegalArgumentException("No such AuroraConfig $name")
        } catch (e: Exception) {
            throw AuroraConfigServiceException("An unexpected error occurred when checking out AuroraConfig with name $name", e)
        }
    }
}