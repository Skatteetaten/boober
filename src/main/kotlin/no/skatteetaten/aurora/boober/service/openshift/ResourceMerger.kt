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
        // "namespace" -> updateNamespace(mergedResource, existingResource)
    }
    return mergedResource
}

private fun updateBuildConfig(mergedResource: JsonNode, existingResource: JsonNode) {
    val triggerCount = (mergedResource.at("/spec/triggers") as ArrayNode).size()
    (0..triggerCount).forEach {
        mergedResource.updateField(existingResource, "/spec/triggers/$it/imageChange", "lastTriggeredImageID")
    }
}

private fun updateDeploymentConfig(mergedResource: JsonNode, existingResource: JsonNode) {
    mergedResource.updateField(existingResource, "/spec/triggers/0/imageChangeParams", "lastTriggeredImage")
    val containerCount = (mergedResource.at("/spec/template/spec/containers") as ArrayNode).size()
    (0..containerCount).forEach {
        mergedResource.updateField(existingResource, "/spec/template/spec/containers/$it", "image")
    }
}

private fun updatePersistentVolumeClaim(mergedResource: JsonNode, existingResource: JsonNode) {
    mergedResource.updateField(existingResource, "/spec", "volumeName")
}

private fun updateService(mergedResource: JsonNode, existingResource: JsonNode) {
    mergedResource.updateField(existingResource, "/spec", "clusterIP")
}

private fun updateNamespace(mergedResource: ObjectNode, existingResource: ObjectNode) =
    mergedResource.mergeField(existingResource, "/metadata", "annotations")
