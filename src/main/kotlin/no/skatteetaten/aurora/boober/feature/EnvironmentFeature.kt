package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newNamespace
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newProjectRequest
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.service.internal.RolebindingGenerator
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.utils.Instants
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.springframework.stereotype.Service


// TODO: Add and
@Service
class EnvironmentFeature(val openShiftClient: OpenShiftClient) : Feature {
    override fun handlers(header: AuroraDeploymentContext): Set<AuroraConfigFieldHandler> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    /*
    fun generateNamespace(environment: AuroraDeployEnvironment): JsonNode {

        val namespace = newNamespace {
            metadata {
                val ttl = environment.ttl?.let {
                    val removeInstant = Instants.now + it
                    "removeAfter" to removeInstant.epochSecond.toString()
                }
                labels = mapOf("affiliation" to environment.affiliation).addIfNotNull(ttl)
                name = environment.namespace
            }
        }

        return jacksonObjectMapper().convertValue(namespace)
    }

    private fun validateAdminGroups(deploymentSpecInternal: AuroraDeploymentSpecInternal) {

        val adminGroups: Set<String> = deploymentSpecInternal.environment.permissions.admin.groups ?: setOf()
        adminGroups.takeIf { it.isEmpty() }
                ?.let { throw AuroraDeploymentSpecValidationException("permissions.admin.groups cannot be empty") }

        val openShiftGroups = openShiftClient.getGroups()

        val nonExistantDeclaredGroups = adminGroups.filter { !openShiftGroups.groupExist(it) }
        if (nonExistantDeclaredGroups.isNotEmpty()) {
            throw AuroraDeploymentSpecValidationException("$nonExistantDeclaredGroups are not valid groupNames")
        }

        val sumMembers = adminGroups.sumBy {
            openShiftGroups.groupUsers[it]?.size ?: 0
        }

        if (0 == sumMembers) {
            throw AuroraDeploymentSpecValidationException("All groups=[${adminGroups.joinToString(", ")}] are empty")
        }
    }

    fun generateDeploymentRequest(name: String): JsonNode {

        val deploymentRequest = mapOf(
                "kind" to "DeploymentRequest",
                "apiVersion" to "apps.openshift.io/v1",
                "name" to name,
                "latest" to true,
                "force" to true
        )

        return jacksonObjectMapper().convertValue(deploymentRequest)
    }

    fun generateProjectRequest(environment: AuroraDeployEnvironment): JsonNode {

        val projectRequest = newProjectRequest {
            metadata {
                name = environment.namespace
            }
        }

        return jacksonObjectMapper().convertValue(projectRequest)
    }

    fun generateRolebindings(permissions: Permissions, namespace: String): List<JsonNode> {

        val admin = RolebindingGenerator.create("admin", permissions.admin, namespace)

        val view = permissions.view?.let {
            RolebindingGenerator.create("view", it, namespace)
        }

        return listOf(admin).addIfNotNull(view).map { jacksonObjectMapper().convertValue<JsonNode>(it) }
    }
*/
}