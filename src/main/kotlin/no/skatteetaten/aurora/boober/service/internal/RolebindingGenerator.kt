package no.skatteetaten.aurora.boober.service.internal

import com.fkorotkov.kubernetes.objectReference
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.roleBinding
import com.fkorotkov.openshift.roleRef
import io.fabric8.kubernetes.api.model.ObjectReference
import io.fabric8.openshift.api.model.RoleBinding
import no.skatteetaten.aurora.boober.model.Permission

object RolebindingGenerator {

    fun create(rolebindingName: String, permission: Permission): RoleBinding {

        return roleBinding {
            apiVersion = "v1"
            metadata {
                name = rolebindingName
            }

            permission.groups?.let {
                groupNames = it.toList()
            }
            permission.users?.let {
                userNames = it.toList()
            }

            val userRefeerences: List<ObjectReference> = permission.users?.map {
                objectReference {
                    kind = "User"
                    name = it
                }
            } ?: emptyList()
            val groupRefeerences = permission.groups?.map {
                objectReference {
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