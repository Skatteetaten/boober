package no.skatteetaten.aurora.boober.facade

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.controller.internal.SetupParams
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.model.AuroraSecretVault
import no.skatteetaten.aurora.boober.model.DeployCommand
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.model.TemplateType.development
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.OpenShiftObjectGenerator
import no.skatteetaten.aurora.boober.service.SecretVaultService
import no.skatteetaten.aurora.boober.service.internal.ApplicationResult
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class SetupFacade(
        val auroraConfigService: AuroraConfigService,
        val openShiftObjectGenerator: OpenShiftObjectGenerator,
        val openShiftClient: OpenShiftClient,
        val gitService: GitService,
        val secretVaultService: SecretVaultService,
        val auroraConfigFacade: AuroraConfigFacade,
        @Value("\${openshift.cluster}") val cluster: String) {

    val logger: Logger = LoggerFactory.getLogger(SetupFacade::class.java)

    fun executeSetup(affiliation: String, setupParams: SetupParams): List<ApplicationResult> {
        val repo = gitService.checkoutRepoForAffiliation(affiliation)

        val auroraConfig = auroraConfigFacade.createAuroraConfig(repo, affiliation, setupParams.overrides)

        val vaults = secretVaultService.getVaults(repo)

        gitService.closeRepository(repo)
        return performSetup(auroraConfig, setupParams.applicationIds, vaults)
    }

    fun performSetup(auroraConfig: AuroraConfig, applicationIds: List<DeployCommand>, vaults: Map<String, AuroraSecretVault>): List<ApplicationResult> {
        val appIds: List<DeployCommand> = applicationIds
                .takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("Specify applicationId")

        val auroraDcs = auroraConfigService.createAuroraDcs(auroraConfig, appIds, vaults)

        return auroraDcs
                .filter { it.cluster == cluster }
                .map { applyDeploymentConfig(it) }

    }


    private fun applyDeploymentConfig(adc: AuroraDeploymentConfig): ApplicationResult {
        logger.info("Creating OpenShift objects for application ${adc.name} in namespace ${adc.namespace}")

        val openShiftObjects = openShiftObjectGenerator.generateObjects(adc)

        val openShiftResponses: List<OpenShiftResponse> = openShiftClient.applyMany(adc.namespace, openShiftObjects)

        val deployResource: JsonNode? =
                generateRedeployResource(openShiftResponses, adc.type, adc.name)

        val finalResponse = deployResource?.let {
            openShiftResponses + openShiftClient.apply(adc.namespace, it)
        } ?: openShiftResponses

        return ApplicationResult(
                applicationId = DeployCommand(ApplicationId(adc.envName, adc.name)),
                auroraDc = adc,
                openShiftResponses = finalResponse
        )
    }

    fun generateRedeployResource(openShiftResponses: List<OpenShiftResponse>, type: TemplateType, name: String): JsonNode? {

        val imageStream = openShiftResponses.find { it.kind == "imagestream" }
        val deployment = openShiftResponses.find { it.kind == "deploymentconfig" }

        val deployResource: JsonNode? =
                if (type == development) {
                    openShiftResponses.filter { it.changed }.firstOrNull()?.let {
                        openShiftObjectGenerator.generateBuildRequest(name)
                    }
                } else if (imageStream == null) {
                    if (deployment != null) {
                        openShiftObjectGenerator.generateDeploymentRequest(name)
                    } else {
                        null
                    }
                } else if (!imageStream.changed && imageStream.operationType == OperationType.UPDATE) {
                    openShiftObjectGenerator.generateDeploymentRequest(name)
                } else {
                    null
                }

        return deployResource
    }
}
