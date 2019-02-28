package no.skatteetaten.aurora.boober.service.internal

import com.fkorotkov.kubernetes.newObjectReference
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newRoleBinding
import com.fkorotkov.openshift.roleRef
import io.fabric8.kubernetes.api.model.ObjectReference
import io.fabric8.openshift.api.model.RoleBinding
import no.skatteetaten.aurora.boober.model.Permission

object RolebindingGenerator {

    fun create(
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
            } ?: emptyList()

            subjects = userRefeerences + groupRefeerences

            roleRef {
                name = rolebindingName
            }
        }
    }
}