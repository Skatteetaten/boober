package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.AuroraPermissions
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import org.springframework.stereotype.Service

@Service
class SecretVaultPermissionService(
        val openShiftClient: OpenShiftClient,
        val userDetailsProvider: UserDetailsProvider
) {
    fun hasUserAccess(permissions: AuroraPermissions?): Boolean {
        if (permissions == null) {
            return true
        }

        val groupSize = permissions.groups?.size ?: 0
        val userSize = permissions.users?.size ?: 0
        if (groupSize + userSize == 0) {
            return true
        }

        val user = userDetailsProvider.getAuthenticatedUser().username

        val validUser: Boolean = permissions.users?.any { user == it && openShiftClient.isValidUser(user) } ?: false

        val validGroup = permissions.groups?.any { openShiftClient.isUserInGroup(user, it) } ?: false

        return validUser || validGroup

    }
}