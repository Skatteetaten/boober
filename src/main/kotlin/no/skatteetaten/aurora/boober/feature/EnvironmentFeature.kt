package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newNamespace
import com.fkorotkov.kubernetes.newObjectReference
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newOpenshiftRoleBinding
import com.fkorotkov.openshift.newProjectRequest
import com.fkorotkov.openshift.roleRef
import io.fabric8.kubernetes.api.model.Namespace
import io.fabric8.kubernetes.api.model.ObjectReference
import io.fabric8.openshift.api.model.OpenshiftRoleBinding
import io.fabric8.openshift.api.model.ProjectRequest
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.utils.Instants
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.springframework.boot.convert.DurationStyle
import org.springframework.stereotype.Service
import java.time.Duration

val AuroraDeploymentSpec.envTTL: Duration?
    get() = this.getOrNull<String>("env/ttl")?.let {
        DurationStyle.SIMPLE.parse(
            it
        )
    }

data class Permissions(
    val admin: Permission,
    val view: Permission? = null
)

data class Permission(
    val groups: Set<String>?,
    val users: Set<String> = emptySet()
)

@Service
class EnvironmentFeature(
    val openShiftClient: OpenShiftClient,
    val userDetailsProvider: UserDetailsProvider
) : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf()
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {

        val rolebindings = generateRolebindings(adc).map {
            generateResource(it, header = true)
        }.toSet()
        return setOf(
            generateResource(
                generateProjectRequest(adc),
                header = true
            ),

            generateResource(
                generateNamespace(adc),
                header = true
            )
        ).addIfNotNull(rolebindings).toSet()
    }

    fun generateNamespace(adc: AuroraDeploymentSpec): Namespace {
        val ttl = adc.envTTL?.let {
            val removeInstant = Instants.now + it
            "removeAfter" to removeInstant.epochSecond.toString()
        }

        return newNamespace {
            metadata {
                labels = mapOf("affiliation" to adc.affiliation).addIfNotNull(ttl)
                name = adc.namespace
            }
        }
    }

    fun generateProjectRequest(adc: AuroraDeploymentSpec): ProjectRequest {

        return newProjectRequest {
            metadata {
                name = adc.namespace
            }
        }
    }

    fun extractPermissions(deploymentSpec: AuroraDeploymentSpec): Permissions {

        val viewGroups = deploymentSpec.getDelimitedStringOrArrayAsSet("permissions/view", " ")
        val adminGroups = deploymentSpec.getDelimitedStringOrArrayAsSet("permissions/admin", " ")
        // if sa present add to admin users.
        val adminUsers = deploymentSpec.getDelimitedStringOrArrayAsSet("permissions/adminServiceAccount", " ")

        val adminPermission = Permission(adminGroups, adminUsers)
        val viewPermission = if (viewGroups.isNotEmpty()) Permission(viewGroups) else null

        return Permissions(admin = adminPermission, view = viewPermission)
    }

    fun generateRolebindings(adc: AuroraDeploymentSpec): List<OpenshiftRoleBinding> {

        val permissions = extractPermissions(adc)

        val admin = createRoleBinding("admin", permissions.admin, adc.namespace)

        val view = permissions.view?.let {
            createRoleBinding("view", it, adc.namespace)
        }

        return listOf(admin).addIfNotNull(view)
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        cmd: AuroraContextCommand
    ): List<Exception> {

        val errors: List<Exception> = try {
            validateAdminGroups(adc)
            emptyList()
        } catch (e: Exception) {
            listOf(e)
        }

        if (!fullValidation) return errors

        val authenticatedUser = userDetailsProvider.getAuthenticatedUser()

        val permissions = extractPermissions(adc)
        val userNotInAdminUsers = !permissions.admin.users.contains(authenticatedUser.username)
        val adminGroups = permissions.admin.groups
        val userNotInAnyAdminGroups = !authenticatedUser.hasAnyRole(adminGroups)

        if (userNotInAdminUsers && userNotInAnyAdminGroups) {
            return errors.addIfNotNull(IllegalArgumentException("User=${authenticatedUser.fullName} does not have access to admin this environment from the groups=$adminGroups"))
        }
        return errors
    }

    private fun validateAdminGroups(adc: AuroraDeploymentSpec) {
        val permissions = extractPermissions(adc)

        val adminGroups: Set<String> = permissions.admin.groups ?: setOf()
        if (adminGroups.isEmpty()) {
            throw AuroraDeploymentSpecValidationException("permissions.admin cannot be empty")
        }

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

    fun createRoleBinding(
        rolebindingName: String,
        permission: Permission,
        rolebindingNamespace: String
    ): OpenshiftRoleBinding {

        return newOpenshiftRoleBinding {
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
}
