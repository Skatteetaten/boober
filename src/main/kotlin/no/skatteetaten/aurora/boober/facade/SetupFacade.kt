package no.skatteetaten.aurora.boober.facade

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.controller.internal.SetupParams
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraSecretVault
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.model.TemplateType.build
import no.skatteetaten.aurora.boober.model.TemplateType.development
import no.skatteetaten.aurora.boober.service.DockerService
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.OpenShiftObjectGenerator
import no.skatteetaten.aurora.boober.service.internal.AuroraApplicationCommand
import no.skatteetaten.aurora.boober.service.internal.AuroraApplicationResult
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

class DeployBundle(
        val repo: Git,
        var auroraConfig: AuroraConfig,
        val vaults: Map<String, AuroraSecretVault>,
        val overrideFiles: List<AuroraConfigFile> = listOf()
)

@Service
class SetupFacade(
        val openShiftObjectGenerator: OpenShiftObjectGenerator,
        val openShiftClient: OpenShiftClient,
        val gitService: GitService,
        val deployBundleService: DeployBundleService,
        val mapper: ObjectMapper,
        val dockerService: DockerService,
        @Value("\${openshift.cluster}") val cluster: String,
        @Value("\${boober.docker.registry}") val dockerRegistry: String) {

    val logger: Logger = LoggerFactory.getLogger(SetupFacade::class.java)

    private val DEPLOY_PREFIX = "DEPLOY"


    fun executeSetup(affiliation: String, setupParams: SetupParams): List<AuroraApplicationResult> {

        return withDeployBundle(affiliation, setupParams.overrides, {
            val applications = createApplicationCommands(it, setupParams.applicationIds)
            val res = applications.map { setupApplication(it, setupParams.deploy) }
            markRelease(res, it.repo)
            res
        })
    }

    fun dryRun(affiliation: String, setupParams: SetupParams): List<AuroraApplicationCommand> {

        return withDeployBundle(affiliation, setupParams.overrides, {
            createApplicationCommands(it, setupParams.applicationIds)
        })
    }

    fun <T> withDeployBundle(affiliation: String, overrideFiles: List<AuroraConfigFile> = listOf(), function: (DeployBundle) -> T): T {

        val deployBundle = deployBundleService.createDeployBundle(affiliation, overrideFiles)
        val res = function(deployBundle)
        gitService.closeRepository(deployBundle.repo)
        return res
    }


    private fun createApplicationCommands(deployBundle: DeployBundle, applicationIds: List<ApplicationId>): List<AuroraApplicationCommand> {
        if (applicationIds.isEmpty()) {
            throw IllegalArgumentException("Specify applicationId")
        }
        val deployId = UUID.randomUUID().toString()
        val auroraDcs = deployBundleService.createAuroraApplications(deployBundle, applicationIds)

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

                    AuroraApplicationCommand(deployId, adc, openShiftCommand, tagCmd)
                })
    }

    private fun setupApplication(cmd: AuroraApplicationCommand, deploy: Boolean): AuroraApplicationResult {
        val auroraDc = cmd.auroraApplication
        val responses = cmd.commands.map {
            openShiftClient.performOpenShiftCommand(it, auroraDc.namespace)
        }

        if (auroraDc.deploy == null) {
            throw NullPointerException("Deploy should not be null")
        }

        val docker = "$dockerRegistry/${auroraDc.deploy.dockerImagePath}:${auroraDc.deploy.dockerTag}"
        val deployCommand =
                generateRedeployResource(responses, auroraDc.type, auroraDc.name, docker, deploy)
                        ?.let {
                            openShiftClient.prepare(auroraDc.namespace, it)
                        }?.let {
                    openShiftClient.performOpenShiftCommand(it, auroraDc.namespace)
                }

        val deleteObjects = openShiftClient.createOpenshiftDeleteCommands(auroraDc.name, auroraDc.namespace, cmd.deployId)
                .map { openShiftClient.performOpenShiftCommand(it, auroraDc.namespace) }


        val responseWithDelete = responses + deleteObjects

        val finalResponses = deployCommand?.let {
            responseWithDelete + it
        } ?: responseWithDelete

        val result = cmd.tagCommand?.let { dockerService.tag(it) }

        return AuroraApplicationResult(cmd.deployId, auroraDc, finalResponses, result)

    }


    private fun markRelease(res: List<AuroraApplicationResult>, repo: Git) {


        res.forEach {

            //TODO: MARK FAILURE
            val result = filterSensitiveInformation(it)
            gitService.markRelease(repo, "$DEPLOY_PREFIX/${it.tag}", mapper.writeValueAsString(result))
        }

        gitService.push(repo)
    }

    private fun filterSensitiveInformation(result: AuroraApplicationResult): AuroraApplicationResult {

        val filteredResponses = result.openShiftResponses.filter { it.responseBody.get("kind").asText() != "Secret" }
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
