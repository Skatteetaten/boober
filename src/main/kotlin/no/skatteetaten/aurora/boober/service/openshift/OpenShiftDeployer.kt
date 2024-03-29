package no.skatteetaten.aurora.boober.service.openshift

import java.time.Duration
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newNamespace
import com.fkorotkov.kubernetes.newObjectReference
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newProjectRequest
import com.fkorotkov.openshift.newRoleBinding
import com.fkorotkov.openshift.roleRef
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Namespace
import io.fabric8.kubernetes.api.model.ObjectReference
import io.fabric8.kubernetes.api.model.PodTemplateSpec
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.ProjectRequest
import io.fabric8.openshift.api.model.RoleBinding
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.feature.ApplicationDeploymentFeature
import no.skatteetaten.aurora.boober.feature.Permission
import no.skatteetaten.aurora.boober.feature.Permissions
import no.skatteetaten.aurora.boober.feature.affiliation
import no.skatteetaten.aurora.boober.feature.deployState
import no.skatteetaten.aurora.boober.feature.dockerImagePath
import no.skatteetaten.aurora.boober.feature.envTTL
import no.skatteetaten.aurora.boober.feature.name
import no.skatteetaten.aurora.boober.feature.namespace
import no.skatteetaten.aurora.boober.feature.pause
import no.skatteetaten.aurora.boober.feature.permissions
import no.skatteetaten.aurora.boober.feature.releaseTo
import no.skatteetaten.aurora.boober.feature.type
import no.skatteetaten.aurora.boober.feature.version
import no.skatteetaten.aurora.boober.model.AuroraDeployCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraDeployResult
import no.skatteetaten.aurora.boober.service.AuroraEnvironmentResult
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.service.OpenShiftCommandService
import no.skatteetaten.aurora.boober.service.RedeployService
import no.skatteetaten.aurora.boober.service.TagCommand
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.Instants
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.normalizeLabels
import no.skatteetaten.aurora.boober.utils.openshiftKind
import no.skatteetaten.aurora.boober.utils.parallelMap
import no.skatteetaten.aurora.boober.utils.whenFalse

private val logger = KotlinLogging.logger { }

data class EnvironmentSpec(
    val affiliation: String,
    val namespace: String,
    val permissions: Permissions,
    val timeToLive: Duration? = null
)

fun AuroraDeploymentSpec.toEnvironmentSpec(): EnvironmentSpec {
    return EnvironmentSpec(this.affiliation, this.namespace, this.permissions, this.envTTL)
}

@Service
class OpenShiftDeployer(
    val openShiftCommandBuilder: OpenShiftCommandService,
    val openShiftClient: OpenShiftClient,
    val cantusService: CantusService,
    val redeployService: RedeployService,
    val userDetailsProvider: UserDetailsProvider,
    @Value("\${integrations.docker.registry}") val dockerRegistry: String,
    @Value("\${integrations.docker.releases}") val releaseDockerRegistry: String,
    @Value("\${boober.projectrequest.sleep:2000}") val projectRequestSleep: Long
) {
    fun performDeployCommands(
        environmentResults: Map<String, AuroraEnvironmentResult>,
        deployCommands: List<AuroraDeployCommand>
    ): List<AuroraDeployResult> {

        val envDeploys: Map<String, List<AuroraDeployCommand>> = deployCommands.groupBy { it.context.spec.namespace }

        return envDeploys.flatMap { (ns, commands) ->
            val env = environmentResults[ns]

            if (env?.success != true) {
                commands.map {
                    AuroraDeployResult(
                        projectExist = env?.projectExist ?: false,
                        deployCommand = it,
                        success = env?.success ?: false,
                        reason = env?.reason ?: "No environment result for namespace=$ns",
                        openShiftResponses = env?.openShiftResponses ?: emptyList()
                    )
                }
            } else {
                commands.parallelMap {
                    val result = deployFromSpec(it, env.projectExist)
                    result.copy(openShiftResponses = env.openShiftResponses.addIfNotNull(result.openShiftResponses))
                }
            }
        }
    }

    fun prepareDeployEnvironment(envSpec: EnvironmentSpec): AuroraEnvironmentResult {

        val namespace = envSpec.namespace
        logger.debug { "Create env with name $namespace" }

        val projectRequest = generateProjectRequest(envSpec)
        val otherEnvResources = setOf(generateNamespace(envSpec)) + generateRolebindings(envSpec)

        val authenticatedUser = userDetailsProvider.getAuthenticatedUser()

        val projectExist = openShiftClient.projectExists(namespace)
        val projectResponse = projectExist.whenFalse {
            val cmd = openShiftCommandBuilder.createOpenShiftCommand(
                newResource = projectRequest,
                mergeWithExistingResource = false,
                retryGetResourceOnFailure = false
            )
            openShiftClient.performOpenShiftCommand(cmd)
                .also { Thread.sleep(projectRequestSleep) }
        }
        val otherResponses = otherEnvResources.map {
            val cmd = openShiftCommandBuilder.createOpenShiftCommand(
                namespace = it.metadata.namespace,
                newResource = it,
                retryGetResourceOnFailure = true
            )
            openShiftClient.performOpenShiftCommand(cmd)
        }
        val allResponses = listOfNotNull(projectResponse).addIfNotNull(otherResponses)

        val success = allResponses.all { it.success }

        val message = if (!success) "One or more http calls to OpenShift failed" else "Namespace created successfully."

        logger.info("Environment done. user='${authenticatedUser.fullName}' namespace=$namespace success=$success reason=$message")

        return AuroraEnvironmentResult(
            openShiftResponses = allResponses,
            success = success,
            reason = message,
            projectExist = projectExist
        )
    }

    private fun deployFromSpec(
        cmd: AuroraDeployCommand,
        projectExist: Boolean
    ): AuroraDeployResult {

        val warnings = cmd.context.warnings
        logger.debug { "Apply application ${cmd.context.cmd.applicationDeploymentRef}" }
        val context = cmd.context
        val resources = cmd.resources
        val application = resources.first {
            it.resource.kind == "ApplicationDeployment"
        }.resource
        application.metadata.labels = application.metadata.labels.addIfNotNull("booberDeployId" to cmd.deployId)
        val applicationResult = applyResource(context.spec.namespace, application)
        val appResponse = applicationResult.responseBody

        if (appResponse == null) {
            return AuroraDeployResult(
                deployCommand = cmd,
                openShiftResponses = listOf(applicationResult),
                success = false,
                reason = "Creating application object failed",
                warnings = warnings
            )
        }

        val ownerReferenceUid = appResponse.at("/metadata/uid").textValue()

        val tagResult = context.spec.takeIf { it.releaseTo != null }?.let {
            cantusService.tag(
                TagCommand(
                    name = it.dockerImagePath,
                    from = it.version,
                    to = it.releaseTo!!,
                    fromRegistry = dockerRegistry,
                    toRegistry = releaseDockerRegistry
                )
            )
        }

        val rawResult = AuroraDeployResult(
            tagResponse = tagResult,
            deployCommand = cmd,
            projectExist = projectExist,
            warnings = warnings
        )

        if (!applicationResult.success) {
            val reason = appResponse.at("/message").textValue()
            return rawResult.copy(success = false, reason = reason)
        }

        logger.info("TagResult=${tagResult?.success}")
        if (tagResult != null && !tagResult.success) {
            logger.info("tag result not successfull")
            return rawResult.copy(
                success = false,
                reason = "Tag command failed."
            )
        }

        logger.debug("Apply objects")
        val openShiftResponses: List<OpenShiftResponse> = listOf(applicationResult) +
            applyOpenShiftApplicationObjects(
                cmd, projectExist, ownerReferenceUid
            )

        logger.debug("done applying objects")
        val success = openShiftResponses.all { it.success }
        val result = rawResult.copy(
            openShiftResponses = openShiftResponses,
            success = success
        )

        if (!success) {
            val failedCommands = openShiftResponses.filter { !it.success }.describeString()
            return result.copy(reason = "Errors $failedCommands")
        }

        if (!cmd.shouldDeploy) {
            return result.copy(reason = "No deploy made, turned off in payload.")
        }

        if (context.spec.pause) {
            return result.copy(reason = "Deployment is paused and will be/remain scaled down.")
        }

        val redeployResult =
            redeployService.triggerRedeploy(openShiftResponses, context.spec.type, context.spec.deployState)

        if (!redeployResult.success) {
            return result.copy(
                openShiftResponses = openShiftResponses.addIfNotNull(redeployResult.openShiftResponses),
                tagResponse = tagResult, success = false, reason = redeployResult.message
            )
        }

        return result.copy(
            openShiftResponses = openShiftResponses.addIfNotNull(redeployResult.openShiftResponses),
            tagResponse = tagResult,
            reason = redeployResult.message
        )
    }

    fun applyResource(namespace: String, resource: HasMetadata): OpenShiftResponse {
        val applicationCommand = openShiftCommandBuilder.createOpenShiftCommand(namespace, resource)
        return openShiftClient.performOpenShiftCommand(applicationCommand)
    }

    private fun applyOpenShiftApplicationObjects(
        deployCommand: AuroraDeployCommand,
        mergeWithExistingResource: Boolean,
        ownerReferenceUid: String
    ): List<OpenShiftResponse> {

        val context = deployCommand.context
        val namespace = context.spec.namespace
        val name = context.spec.name

        fun getPodTemplateIfApplicable(resource: HasMetadata): PodTemplateSpec? {
            return when (resource) {
                is Deployment -> resource.spec.template
                is DeploymentConfig -> resource.spec.template
                else -> null
            }
        }

        val jsonResources = deployCommand.resources.filter {
            it.createdSource.feature != ApplicationDeploymentFeature::class.java
        }.map { resource ->
            val podTemplateSpec = getPodTemplateIfApplicable(resource.resource)
            if (podTemplateSpec != null) {
                podTemplateSpec.metadata.labels =
                    podTemplateSpec.metadata.labels.addIfNotNull("booberDeployId" to deployCommand.deployId)
            }

            resource.resource.metadata.labels =
                resource.resource.metadata.labels.addIfNotNull("booberDeployId" to deployCommand.deployId)
            resource.resource.metadata.ownerReferences.find {
                it.kind == "ApplicationDeployment"
            }?.let {
                it.uid = ownerReferenceUid
            }
            jacksonObjectMapper().convertValue<JsonNode>(resource.resource)
        }

        val objects = openShiftCommandBuilder.orderObjects(
            jsonResources,
            context.spec.type,
            context.spec.namespace,
            mergeWithExistingResource
        )

        val shouldSleepBeforeDc = objects.last().openshiftKind == "deploymentconfig"
        val openShiftApplicationResponses: List<OpenShiftResponse> = objects.flatMap {

            if (it.openshiftKind == "deploymentconfig" && shouldSleepBeforeDc) {
                Thread.sleep(500)
            }

            openShiftCommandBuilder.createAndApplyObjects(namespace, it, mergeWithExistingResource)
        }

        if (openShiftApplicationResponses.any { !it.success }) {
            logger.warn("One or more commands failed for $namespace/$name. Will not delete objects from previous deploys.")
            return openShiftApplicationResponses
        }

        val deleteOldObjectResponses = openShiftCommandBuilder
            .createOpenShiftDeleteCommands(name, namespace, deployCommand.deployId)
            .map { openShiftClient.performOpenShiftCommand(it) }

        return openShiftApplicationResponses.addIfNotNull(deleteOldObjectResponses)
    }
}

fun generateNamespace(adc: EnvironmentSpec): Namespace {
    val ttl = adc.timeToLive?.let {
        val removeInstant = Instants.now + it
        "removeAfter" to removeInstant.epochSecond.toString()
    }

    return newNamespace {
        metadata {
            labels = mapOf("affiliation" to adc.affiliation).addIfNotNull(ttl).normalizeLabels()
            name = adc.namespace
        }
    }
}

fun generateProjectRequest(adc: EnvironmentSpec): ProjectRequest {

    return newProjectRequest {
        metadata {
            name = adc.namespace
        }
    }
}

fun generateRolebindings(adc: EnvironmentSpec): Set<RoleBinding> {

    val permissions = adc.permissions

    val admin = createRoleBinding("admin", permissions.admin, adc.namespace)

    val view = permissions.view?.let {
        createRoleBinding("view", it, adc.namespace)
    }

    val edit = permissions.edit?.let {
        createRoleBinding("edit", it, adc.namespace)
    }

    return listOf(admin).addIfNotNull(view).addIfNotNull(edit).toSet()
}

fun createRoleBinding(
    rolebindingName: String,
    permission: Permission,
    rolebindingNamespace: String
): RoleBinding {

    return newRoleBinding {
        metadata {
            name = rolebindingName
            namespace = rolebindingNamespace
        }

        permission.groups?.let {
            groupNames = it.toList()
        }
        permission.users.let {
            userNames = it.toList()
        }

        val userRefeerences: List<ObjectReference> = permission.users.map {
            newObjectReference {
                kind = "User"
                name = it
            }
        }
        val groupRefeerences = permission.groups?.map {
            newObjectReference {
                kind = "Group"
                name = it
            }
        }

        subjects = userRefeerences.addIfNotNull(groupRefeerences)

        roleRef {
            name = rolebindingName
        }
    }
}
