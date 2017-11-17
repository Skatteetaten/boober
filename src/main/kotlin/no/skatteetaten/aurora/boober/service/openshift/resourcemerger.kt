package no.skatteetaten.aurora.boober.service.openshift

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import no.skatteetaten.aurora.boober.utils.openshiftKind
import no.skatteetaten.aurora.boober.utils.updateField

fun mergeWithExistingResource(newResource: JsonNode, existingResource: JsonNode): JsonNode {

    val mergedResource = newResource.deepCopy<JsonNode>()

    val kind = mergedResource.openshiftKind

    mergedResource.updateField(existingResource, "/metadata", "resourceVersion")

    if (kind == "service") {
        mergedResource.updateField(existingResource, "/spec", "clusterIP")
    }

    if (kind == "persistentvolumeclaim") {
        mergedResource.updateField(existingResource, "/spec", "volumeName")
    }

    if (kind == "deploymentconfig") {
        mergedResource.updateField(existingResource, "/spec/triggers/0/imageChangeParams", "lastTriggeredImage")
        val containerCount = (mergedResource.at("/spec/template/spec/containers") as ArrayNode).size()
        (0..containerCount).forEach {
            mergedResource.updateField(existingResource, "/spec/template/spec/containers/$it", "image")
        }
    }

    if (kind == "buildconfig") {
        val triggerCount = (mergedResource.at("/spec/triggers") as ArrayNode).size()
        (0..triggerCount).forEach {
            mergedResource.updateField(existingResource, "/spec/triggers/$it/imageChange", "lastTriggeredImageID")
        }
    }

    return mergedResource
}