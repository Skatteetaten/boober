package no.skatteetaten.aurora.boober.facade

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.controller.internal.SetupParams
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraSecretVault
import no.skatteetaten.aurora.boober.model.DeployCommand
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.model.TemplateType.build
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
    private val FAILED_PREFIX = "FAILED"


    fun executeSetup(affiliation: String, setupParams: SetupParams): List<ApplicationResult> {
        val repo = gitService.checkoutRepoForAffiliation(affiliation)

        val applications = getDeployCommands(affiliation, setupParams, repo)

        val res = applications.map { setupApplication(it, setupParams.deploy) }

        markRelease(res, repo)
        gitService.closeRepository(repo)
        return res

    }

    fun setupApplication(cmd: ApplicationCommand, deploy: Boolean): ApplicationResult {

        //TODO: if we do not want to try another command after the first failed we have to do a manual
        //loop here and append to a list, stop if we have a failure and return what we have so far and the failure.
        //the question is what do we really want here. Is it not nice to know all failures in some situatjons?
        //note that a deploy/build/import command is never run if this fails.
        val responses = cmd.commands.map {
            openShiftClient.performOpenShiftCommand(it, cmd.auroraDc.namespace)
        }

        if (responses.any { !it.success }) {
            return ApplicationResult(cmd.deployId, cmd.auroraDc, responses, success = false)
        }

        if (cmd.auroraDc.deploy == null) {
            throw NullPointerException("Deploy should not be null")
        }
        val docker = "$dockerRegistry/${cmd.auroraDc.deploy.dockerImagePath}:${cmd.auroraDc.deploy.dockerTag}"
        val deployCommand =
                generateRedeployResource(responses, cmd.auroraDc.type, cmd.auroraDc.name, docker, deploy)
                        ?.let {
                            openShiftClient.prepare(cmd.auroraDc.namespace, it)
                        }?.let {
                    openShiftClient.performOpenShiftCommand(it, cmd.auroraDc.namespace)
                }

        val deleteObjects = openShiftClient.createOpenshiftDeleteCommands(cmd.auroraDc.name, cmd.auroraDc.namespace, cmd.deployId)
                .map { openShiftClient.performOpenShiftCommand(it, cmd.auroraDc.namespace) }


        val responseWithDelete = responses + deleteObjects

        //TODO: fix with extention method
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

        return LinkedList(auroraDcs
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


                    //Her kan vi lage deploy/build/importImage kommando
                    val tagCmd = adc.deploy?.takeIf { it.releaseTo != null }?.let {
                        val dockerGroup = it.groupId.replace(".", "_")
                        TagCommand("${dockerGroup}/${it.artifactId}", it.version, it.releaseTo!!, dockerRegistry)
                    }

                    ApplicationCommand(deployId, adc, openShiftCommand, tagCmd)
                })
    }


    //TODO: test failure
    fun markRelease(res: List<ApplicationResult>, repo: Git) {

        res.forEach {

            //TODO: MARK FAILURE
            val result = filterSensitiveInformation(it)
            val prefix = if (it.success) {
                DEPLOY_PREFIX
            } else {
                FAILED_PREFIX
            }
            gitService.markRelease(repo, "$prefix/${it.tag}", mapper.writeValueAsString(result))
        }

        gitService.push(repo)
    }

    private fun filterSensitiveInformation(result: ApplicationResult): ApplicationResult {
        val filteredResponses = result.openShiftResponses.filter { it.responseBody?.get("kind")?.asText() != "Secret" }
        return result.copy(openShiftResponses = filteredResponses)

    }


    fun generateRedeployResource(openShiftResponses: List<OpenShiftResponse>,
                                 type: TemplateType,
                                 name: String,
                                 docker: String,
                                 deploy: Boolean): JsonNode? {

        if (!deploy) {
            return null
        }

        if (type == build) {
            return null
        }

        if (type == development) {
            return openShiftObjectGenerator.generateBuildRequest(name)
        }

        val imageStream = openShiftResponses.find { it.responseBody["kind"].asText().toLowerCase() == "imagestream" }
        val deployment = openShiftResponses.find { it.responseBody["kind"].asText().toLowerCase() == "deploymentconfig" }

        imageStream?.takeIf { !it.labelChanged("releasedVersion") && it.command.operationType == OperationType.UPDATE }?.let {
            return openShiftObjectGenerator.generateDeploymentRequest(name)
        }

        if (imageStream == null) {
            return deployment?.let {
                openShiftObjectGenerator.generateDeploymentRequest(name)
            }
        }
        return openShiftObjectGenerator.generateImageStreamImport(name, docker)

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
