package no.skatteetaten.aurora.boober.feature

import java.time.Duration
import org.springframework.boot.convert.DurationStyle
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec

val AuroraDeploymentSpec.envTTL: Duration?
    get() = this.getOrNull<String>("env/ttl")
        ?.let { DurationStyle.SIMPLE.parse(it) }

data class Permissions(
    val admin: Permission,
    val view: Permission? = null,
    val edit: Permission? = null
)

data class Permission(
    val groups: Set<String>?,
    val users: Set<String> = emptySet()
)

val AuroraDeploymentSpec.permissions: Permissions
    get() {
        val viewGroups = getDelimitedStringOrArrayAsSet("permissions/view", " ")
        val editGroups = getDelimitedStringOrArrayAsSet("permissions/edit", " ")
        val adminGroups = getDelimitedStringOrArrayAsSet("permissions/admin", " ")
        // if sa present add to admin users.
        val adminUsers = getDelimitedStringOrArrayAsSet("permissions/adminServiceAccount", " ")

        val adminPermission = Permission(adminGroups, adminUsers)
        val viewPermission = if (viewGroups.isNotEmpty()) Permission(viewGroups) else null
        val editPermission = if (editGroups.isNotEmpty()) Permission(editGroups) else null

        return Permissions(admin = adminPermission, view = viewPermission, edit = editPermission)
    }
