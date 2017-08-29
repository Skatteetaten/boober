package no.skatteetaten.aurora.boober.facade

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.controller.internal.SetupParams
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraSecretVault
import no.skatteetaten.aurora.boober.model.DeployCommand
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.model.TemplateType.development
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.DockerService
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.OpenShiftObjectGenerator
import no.skatteetaten.aurora.boober.service.internal.ApplicationCommand
import no.skatteetaten.aurora.boober.service.internal.ApplicationResult
import no.skatteetaten.aurora.boober.service.internal.DeployHistory
import no.skatteetaten.aurora.boober.service.internal.TagCommand
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
        val secretVaultFacade: VaultFacade,
        val auroraConfigFacade: AuroraConfigFacade,
        val mapper: ObjectMapper,
        val dockerService: DockerService,
        @Value("\${openshift.cluster}") val cluster: String,
        @Value("\${boober.docker.registry}") val dockerRegistry: String) {

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
        val responses = cmd.commands.map {
            openShiftClient.performOpenShiftCommand(it, cmd.auroraDc.namespace)
        }

        val deployCommand =
                generateRedeployResource(responses, cmd.auroraDc.type, cmd.auroraDc.name)
                        ?.let {
                            openShiftClient.prepare(cmd.auroraDc.namespace, it)
                        }?.let {
                    openShiftClient.performOpenShiftCommand(it, cmd.auroraDc.namespace)
                }

        val deleteObjects = openShiftClient.createOpenshiftDeleteCommands(cmd.auroraDc.name, cmd.auroraDc.namespace, cmd.deployId)
                .map { openShiftClient.performOpenShiftCommand(it, cmd.auroraDc.namespace) }


        val responseWithDelete = responses + deleteObjects

        val finalResponses = deployCommand?.let {
            responseWithDelete + it
        } ?: responseWithDelete

        val result = cmd.tagCommand?.let { dockerService.tag(it) }

        return ApplicationResult(cmd.deployId, cmd.auroraDc, finalResponses, result)

    }


    fun getDeployCommands(affiliation: String, setupParams: SetupParams, git: Git? = null): LinkedList<ApplicationCommand> {

        val appIds: List<DeployCommand> = setupParams.applicationIds
                .takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("Specify applicationId")

        val repo = git ?: gitService.checkoutRepoForAffiliation(affiliation)

        val auroraConfig = auroraConfigFacade.createAuroraConfig(repo, affiliation)

        val vaults = secretVaultFacade.listVaults(affiliation, repo).associateBy { it.name }

        val deployId = UUID.randomUUID().toString()

        val res = createApplicationCommands(auroraConfig, appIds, vaults, deployId)

        if (git == null) {
            gitService.closeRepository(repo)
        }
        return res


    }

    fun createApplicationCommands(auroraConfig: AuroraConfig, appIds: List<DeployCommand>, vaults: Map<String, AuroraSecretVault>, deployId: String): LinkedList<ApplicationCommand> {
        val auroraDcs = auroraConfigService.createAuroraDcs(auroraConfig, appIds, vaults)

        val res = LinkedList(auroraDcs
                .filter { it.cluster == cluster }
                .map { adc ->
                    //her kan vi ikke gjøre det på denne måten.
                    //vi må finne ut om prosjektet finnes.
                    val openShiftObjects = openShiftObjectGenerator.generateObjects(adc, deployId)

                    //we need to check if there is a project that you cannot view/operate on
                    val openShiftCommand = if (openShiftClient.projectExist(adc.namespace)) {
                        openShiftObjects.mapNotNull { openShiftClient.prepare(adc.namespace, it) }
                    } else {
                        openShiftObjects.mapNotNull { OpenshiftCommand(OperationType.CREATE, payload = it) }
                    }

                    val tagCmd = adc.deploy?.let {
                        if (it.releaseTo != null) {
                            val dockerGroup = it.groupId.replace(".", "_")
                            TagCommand("${dockerGroup}/${it.artifactId}", it.version, it.releaseTo, dockerRegistry)
                        } else null
                    }

                    ApplicationCommand(deployId, adc, openShiftCommand, tagCmd)
                })
        return res
    }


    fun markRelease(res: List<ApplicationResult>, repo: Git) {


        res.forEach {

            val result = filterSensitiveInformation(it)
            gitService.markRelease(repo, "$DEPLOY_PREFIX/${it.tag}", mapper.writeValueAsString(result))
        }

        gitService.push(repo)
    }

    private fun filterSensitiveInformation(result: ApplicationResult): ApplicationResult {
        val filteredResponses = result.openShiftResponses.filter { it.responseBody.get("kind").asText() != "Secret" }
        return result.copy(openShiftResponses = filteredResponses)

    }


    fun generateRedeployResource(openShiftResponses: List<OpenShiftResponse>, type: TemplateType, name: String): JsonNode? {

        val imageStream = openShiftResponses.find { it.responseBody["kind"].asText().toLowerCase() == "imagestream" }
        val deployment = openShiftResponses.find { it.responseBody["kind"].asText().toLowerCase() == "deploymentconfig" }

        val deployResource: JsonNode? =
                if (type == development) {
                    openShiftObjectGenerator.generateBuildRequest(name)
                } else if (imageStream == null) {
                    if (deployment != null) {
                        openShiftObjectGenerator.generateDeploymentRequest(name)
                    } else {
                        null
                    }
                } else if (!imageStream.labelChanged("releasedVersion") && imageStream.command.operationType == OperationType.UPDATE) {
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
