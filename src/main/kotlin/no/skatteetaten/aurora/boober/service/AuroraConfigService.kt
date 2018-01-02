package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.GitServices.Domain.AURORA_CONFIG
import no.skatteetaten.aurora.boober.service.GitServices.TargetDomain
import org.eclipse.jgit.api.errors.InvalidRemoteException

class AuroraConfigService(@TargetDomain(AURORA_CONFIG) val gitService: GitService) {

    fun findAuroraConfig(name: String): AuroraConfig {

        try {
            gitService.checkoutRepository(name)
        } catch(e: InvalidRemoteException) {
            throw IllegalArgumentException("No such AuroraConfig $name")
        } catch (e: Exception) {
            throw AuroraConfigServiceException("An unexpected error occurred when checking out AuroraConfig with name $name", e)
        }
        return AuroraConfig.fromFolder("${gitService.checkoutPath}/$name")
    }
}