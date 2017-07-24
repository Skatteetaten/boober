package no.skatteetaten.aurora.boober.facade

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
import no.skatteetaten.aurora.boober.service.internal.ApplicationCommand
import no.skatteetaten.aurora.boober.service.internal.ApplicationResult
import no.skatteetaten.aurora.boober.service.internal.DeployHistory
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import org.eclipse.jgit.api.Git
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.*

@Service
class SetupFacade(
        val auroraConfigService: AuroraConfigService,
        val openShiftObjectGenerator: OpenShiftObjectGenerator,
        val openShiftClient: OpenShiftClient,
        val gitService: GitService,
        val secretVaultService: SecretVaultService,
        val auroraConfigFacade: AuroraConfigFacade,
        val mapper: ObjectMapper,
        @Value("\${openshift.cluster}") val cluster: String) {

    val logger: Logger = LoggerFactory.getLogger(SetupFacade::class.java)

    private val DEPLOY_PREFIX = "DEPLOY"


    fun executeSetup(affiliation: String, setupParams: SetupParams): List<ApplicationResult> {
        val repo = gitService.checkoutRepoForAffiliation(affiliation)

        val applications = getDeployCommands(affiliation, setupParams, repo)

        val res = applications.map { setupApplication(it) }

        markRelease(res, repo)
        gitService.closeRepository(repo)
        return res

    }

     fun setupApplication(cmd: ApplicationCommand): ApplicationResult {
        val responses = cmd.commands.map { openShiftClient.performOpenshiftCommand(it) }

        val deployResource: JsonNode? =
                generateRedeployResource(responses, cmd.auroraDc.type, cmd.auroraDc.name)

        val deployCommand = deployResource?.let {
            openShiftClient.prepare(cmd.auroraDc.namespace, deployResource)

        }

         //TODO: Refactor this into OpenshiftCommand and OpenshiftReponse
        val deleteObjectUrls = openShiftClient.findOldObjectUrls(cmd.auroraDc.name, cmd.auroraDc.namespace, cmd.deployId)

        deleteObjectUrls.forEach {
            openShiftClient.deleteObject(it)
        }


        return if (deployCommand == null) {
            ApplicationResult(cmd, responses, deleteObjectUrls)
        } else {
            val deployResponse = openShiftClient.performOpenshiftCommand(deployCommand)
            ApplicationResult(cmd.copy(commands = cmd.commands + deployCommand), responses + deployResponse, deleteObjectUrls)

        }
    }


    fun getDeployCommands(affiliation: String, setupParams: SetupParams, git: Git? = null): List<ApplicationCommand> {

        val repo = git ?: gitService.checkoutRepoForAffiliation(affiliation)

        val auroraConfig = auroraConfigFacade.createAuroraConfig(repo, affiliation, setupParams.overrides)

        val vaults = secretVaultService.getVaults(repo)

        val deployId = UUID.randomUUID().toString()

        val appIds: List<DeployCommand> = setupParams.applicationIds
                .takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("Specify applicationId")

        val auroraDcs = auroraConfigService.createAuroraDcs(auroraConfig, appIds, vaults)

        val res = auroraDcs
                .filter { it.cluster == cluster }
                .map {
                    val openShiftObjects = openShiftObjectGenerator.generateObjects(it, deployId)

                    val openshiftCommand = openShiftClient.prepareCommands(it.namespace, openShiftObjects)
                    ApplicationCommand(deployId, it, openshiftCommand, setupParams.overrides)
                }

        if (git == null) {
            gitService.closeRepository(repo)
        }
        return res


    }


    fun markRelease(res: List<ApplicationResult>, repo: Git) {
        res.forEach {
            gitService.markRelease(repo, "$DEPLOY_PREFIX/${it.tag}", mapper.writeValueAsString(it))
        }

        gitService.push(repo)
        gitService.closeRepository(repo)
    }


    fun generateRedeployResource(openShiftResponses: List<OpenShiftResponse>, type: TemplateType, name: String): JsonNode? {

        val imageStream = openShiftResponses.find { it.responseBody["kind"].asText() == "imagestream" }
        val deployment = openShiftResponses.find { it.responseBody["kind"].asText() == "deploymentconfig" }

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
                } else if (!imageStream.changed && imageStream.command.operationType == OperationType.UPDATE) {
                    openShiftObjectGenerator.generateDeploymentRequest(name)
                } else {
                    null
                }

        return deployResource
    }

    fun deployHistory(affiliation: String): List<DeployHistory> {
        val repo = gitService.checkoutRepoForAffiliation(affiliation)
        val res = gitService.tagHistory(repo)
                .filter { it.tagName.startsWith(DEPLOY_PREFIX) }
                .map { DeployHistory(it.taggerIdent, mapper.readTree(it.fullMessage)) }
        gitService.closeRepository(repo)
        return res
    }
}
