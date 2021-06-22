package no.skatteetaten.aurora.boober.feature

import org.springframework.stereotype.Service
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.utils.addIfNotNull

@Service
class EnvironmentFeature(
    val openShiftClient: OpenShiftClient,
    val userDetailsProvider: UserDetailsProvider
) : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> =
        setOf()

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {

        val errors: List<Exception> = try {
            validateAdminGroups(adc)
            emptyList()
        } catch (e: Exception) {
            listOf(e)
        }

        if (!fullValidation) return errors

        val authenticatedUser = userDetailsProvider.getAuthenticatedUser()

        val permissions = adc.permissions
        val userNotInAdminUsers = !permissions.admin.users.contains(authenticatedUser.username)
        val adminGroups = permissions.admin.groups
        val userNotInAnyAdminGroups = !authenticatedUser.hasAnyRole(adminGroups)

        if (userNotInAdminUsers && userNotInAnyAdminGroups) {
            return errors.addIfNotNull(IllegalArgumentException("User=${authenticatedUser.fullName} does not have access to admin this environment from the groups=$adminGroups"))
        }
        return errors
    }

    private fun validateAdminGroups(adc: AuroraDeploymentSpec) {
        val permissions = adc.permissions

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
}
