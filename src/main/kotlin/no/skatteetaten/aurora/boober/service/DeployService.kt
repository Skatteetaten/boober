package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.controller.internal.DeployParams
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.DeployBundle
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.model.TemplateType.build
import no.skatteetaten.aurora.boober.model.TemplateType.development
import no.skatteetaten.aurora.boober.service.internal.AuroraApplicationCommand
import no.skatteetaten.aurora.boober.service.internal.AuroraApplicationResult
import no.skatteetaten.aurora.boober.service.internal.DeployHistory
import no.skatteetaten.aurora.boober.service.internal.TagCommand
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.openshiftKind
import no.skatteetaten.aurora.boober.utils.openshiftName
import org.eclipse.jgit.api.Git
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.*

@Service
class DeployService(
        val openShiftObjectGenerator: OpenShiftObjectGenerator,
        val openShiftClient: OpenShiftClient,
        val gitService: GitService,
        val deployBundleService: DeployBundleService,
        val mapper: ObjectMapper,
        val dockerService: DockerService,
        @Value("\${openshift.cluster}") val cluster: String,
        @Value("\${boober.docker.registry}") val dockerRegistry: String) {

    val logger: Logger = LoggerFactory.getLogger(DeployService::class.java)

    private val DEPLOY_PREFIX = "DEPLOY"
    private val FAILED_PREFIX = "FAILED"


    fun executeDeploy(affiliation: String, deployParams: DeployParams): List<AuroraApplicationResult> {

        return deployBundleService.withDeployBundle(affiliation, deployParams.overrides, {
            val applications = createApplicationCommands(it, deployParams.applicationIds)
            val res = applications.map { setupApplication(it, deployParams.deploy) }
            markRelease(res, it.repo)
            res
        })
    }

    fun dryRun(affiliation: String, deployParams: DeployParams): List<AuroraApplicationCommand> {

        return deployBundleService.withDeployBundle(affiliation, deployParams.overrides, {
            createApplicationCommands(it, deployParams.applicationIds)
        })
    }

    private fun createApplicationCommands(deployBundle: DeployBundle, applicationIds: List<ApplicationId>): List<AuroraApplicationCommand> {
        if (applicationIds.isEmpty()) {
            throw IllegalArgumentException("Specify applicationId")
        }
        val deployId = UUID.randomUUID().toString()
        val auroraApplications = deployBundleService.createAuroraApplications(deployBundle, applicationIds)

        return LinkedList(auroraApplications
                .filter { it.cluster == cluster }
                .map { application ->
                    //her kan vi ikke gjøre det på denne måten.
                    //vi må finne ut om prosjektet finnes.
                    val openShiftObjects = openShiftObjectGenerator.generateObjects(application, deployId)

                    //we need to check if there is a project that you cannot view/operate on

                    val openShiftCommand = if (openShiftClient.projectExist(application.namespace)) {
                        openShiftObjects.mapNotNull { openShiftClient.prepare(application.namespace, it) }
                    } else {
                        openShiftObjects.mapNotNull { OpenshiftCommand(OperationType.CREATE, payload = it) }
                    }


                    //Her kan vi lage deploy/build/importImage kommando
                    val tagCmd = application.deploy?.takeIf { it.releaseTo != null }?.let {
                        val dockerGroup = it.groupId.replace(".", "_")
                        TagCommand("${dockerGroup}/${it.artifactId}", it.version, it.releaseTo!!, dockerRegistry)
                    }

                    AuroraApplicationCommand(deployId, application, openShiftCommand, tagCmd)
                })
    }

    private fun setupApplication(cmd: AuroraApplicationCommand, deploy: Boolean): AuroraApplicationResult {

        val application = cmd.auroraApplication

        val responses = cmd.commands.map {
            val kind = it.payload.openshiftKind
            val name = it.payload.openshiftName

            // ProjectRequest will always create an admin rolebinding, so if we get a command to create one, we just
            // swap it out with an update command.
            val cmd = if (it.operationType == OperationType.CREATE && kind == "rolebinding" && name == "admin") {
                openShiftClient.updateRolebindingCommand(it.payload, application.namespace)
            } else {
                it
            }

            openShiftClient.performOpenShiftCommand(cmd, application.namespace)
        }

        // ProjectRequest will create a Project and a Namespace, but without labels. To get the labels applied we need
        // to update the namespace after the fact.
        val namespaceResponse = if (cmd.commands.any { it.payload.openshiftKind == "projectrequest" && it.operationType == OperationType.CREATE }) {
            val namespaceCommand = openShiftClient.createNamespaceCommand(cmd.auroraApplication.namespace, cmd.auroraApplication.affiliation)
            openShiftClient.performOpenShiftCommand(namespaceCommand, "")
        } else {
            null
        }

        if (responses.any { !it.success }) {
            return AuroraApplicationResult(cmd.deployId, application, responses.addIfNotNull(namespaceResponse), success = false)
        }

        if (application.deploy == null) {
            throw NullPointerException("Deploy should not be null")
        }

        val docker = "${application.deploy.dockerImagePath}:${application.deploy.dockerTag}"
        val deployCommand = generateRedeployResource(responses, application.type, application.name, docker, deploy)
                ?.let { openShiftClient.prepare(application.namespace, it) }
                ?.let { openShiftClient.performOpenShiftCommand(it, application.namespace) }

        val deleteObjects = openShiftClient.createOpenshiftDeleteCommands(application.name, application.namespace, cmd.deployId)
                .map { openShiftClient.performOpenShiftCommand(it, application.namespace) }

        val finalResponses = (responses + deleteObjects).addIfNotNull(deployCommand).addIfNotNull(namespaceResponse)

        val result = cmd.tagCommand?.let { dockerService.tag(it) }

        return AuroraApplicationResult(cmd.deployId, application, finalResponses, result)
    }

    private fun markRelease(res: List<AuroraApplicationResult>, repo: Git) {

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

    private fun filterSensitiveInformation(result: AuroraApplicationResult): AuroraApplicationResult {

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

        val imageStream = openShiftResponses.find { it.responseBody?.get("kind")?.asText()?.toLowerCase() ?: "" == "imagestream" }
        val deployment = openShiftResponses.find { it.responseBody?.get("kind")?.asText()?.toLowerCase() ?: "" == "deploymentconfig" }

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
