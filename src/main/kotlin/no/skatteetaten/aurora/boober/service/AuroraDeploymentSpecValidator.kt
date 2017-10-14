package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import org.springframework.stereotype.Service

@Service
class AuroraDeploymentSpecValidator(val openShiftClient: OpenShiftClient) {

    fun validate(deploymentSpec: AuroraDeploymentSpec) : Boolean {

        /*
        } else if (!openShiftClient.templateExist(template)) {
            IllegalArgumentException("Template $template does not exist in openshift namespace")
*/

        fun validateGroups(openShiftClient: OpenShiftClient, required: Boolean = true): (JsonNode?) -> Exception? {
            return { json ->
                if (required && (json == null || json.textValue().isBlank())) {
                    IllegalArgumentException("Groups must be set")
                } else {
                    val groups = json?.textValue()?.split(" ")?.toSet()
                    groups?.filter { !openShiftClient.isValidGroup(it) }
                            .takeIf { it != null && it.isNotEmpty() }
                            ?.let { AuroraConfigException("The following groups are not valid=${it.joinToString()}") }
                }
            }
        }

        fun validateUsers(openShiftClient: OpenShiftClient): (JsonNode?) -> AuroraConfigException? {
            return { json ->
                val users = json?.textValue()?.split(" ")?.toSet()
                users?.filter { !openShiftClient.isValidUser(it) }
                        .takeIf { it != null && it.isNotEmpty() }
                        ?.let { AuroraConfigException("The following users are not valid=${it.joinToString()}") }
            }
        }

        return true
    }
}