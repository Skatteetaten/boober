package no.skatteetaten.aurora.boober.service.openshift

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.utils.mergeField
import no.skatteetaten.aurora.boober.utils.openshiftKind
import no.skatteetaten.aurora.boober.utils.updateField

/**
 * When generating OpenShift objects that is an update or a replacement for existing object, we sometimes need to
 * preserve some information from the existing objects that has been set by OpenShift. This method will make sure that
 * the required information is preserved.
 */
fun mergeWithExistingResource(newResource: JsonNode, existingResource: JsonNode): JsonNode {

    val mergedResource = newResource.deepCopy<JsonNode>()
    if (mergedResource !is ObjectNode || existingResource !is ObjectNode) throw IllegalArgumentException("Resources must be ObjectNodes")

    val kind = mergedResource.openshiftKind

    mergedResource.updateField(existingResource, "/metadata", "resourceVersion")

    when (kind) {
        "service" -> updateService(mergedResource, existingResource)
        "persistentvolumeclaim" -> updatePersistentVolumeClaim(mergedResource, existingResource)
        "deploymentconfig" -> updateDeploymentConfig(mergedResource, existingResource)
        "buildconfig" -> updateBuildConfig(mergedResource, existingResource)
        "namespace" -> mergeMetadataFrom(mergedResource, existingResource)
        "auroracname" -> mergeMetadataFrom(mergedResource, existingResource)
        "auroraazureapp" -> mergeMetadataFrom(mergedResource, existingResource)
        "auroraapim" -> mergeMetadataFrom(mergedResource, existingResource)
    }
    return mergedResource
}

private fun updateBuildConfig(mergedResource: JsonNode, existingResource: JsonNode) {
    val triggerNode = mergedResource.at("/spec/triggers")

    if (triggerNode is ArrayNode) {
        (0 until triggerNode.size()).forEach {
            mergedResource.updateField(existingResource, "/spec/triggers/$it/imageChange", "lastTriggeredImageID")
        }
    }
}

private fun updateDeploymentConfig(mergedResource: JsonNode, existingResource: JsonNode) {
    mergedResource.updateField(existingResource, "/spec/triggers/0/imageChangeParams", "lastTriggeredImage")
    val containersField = "/spec/template/spec/containers"

    (mergedResource.at(containersField) as ArrayNode).forEachIndexed { i, containerNode ->
        val containerName = containerNode.at("/name").textValue()
        val isApplicationContainer = !containerName.endsWith("-sidecar")

        // We should allow updates of sidecar container images
        if (isApplicationContainer) {
            (existingResource.at(containersField) as ArrayNode)
                .find { it.at("/name").textValue() == containerName }
                ?.let {
                    val sourceField = it.at("/image")
                    if (!sourceField.isMissingNode) {
                        (mergedResource.at("$containersField/$i") as ObjectNode).replace(
                            "image",
                            sourceField
                        )
                    }
                }
        }
    }
}

private fun updatePersistentVolumeClaim(mergedResource: JsonNode, existingResource: JsonNode) {
    mergedResource.updateField(existingResource, "/spec", "volumeName")
}

private fun updateService(mergedResource: JsonNode, existingResource: JsonNode) {
    mergedResource.updateField(existingResource, "/spec", "clusterIP")
}

private fun mergeMetadataFrom(mergedResource: ObjectNode, existingResource: ObjectNode) =
    mergedResource.mergeField(existingResource, "/metadata", "annotations")
