package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import org.springframework.stereotype.Service

data class AuroraPermissions @JvmOverloads constructor(
        val groups: List<String>? = listOf()
)


@Service
class PermissionService(
        val openShiftClient: OpenShiftClient,
        val userDetailsProvider: UserDetailsProvider
) {
    fun hasUserAccess(permissions: AuroraPermissions?): Boolean {
        if (permissions == null || permissions.groups?.isEmpty() != false) {
            return true
        }

        val user = userDetailsProvider.getAuthenticatedUser().username

        return permissions.groups.any { openShiftClient.isUserInGroup(user, it) }
    }
}