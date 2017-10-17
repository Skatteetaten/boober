package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.utils.openshiftKind


fun modifyCommandIfRoute(json: JsonNode): OpenshiftCommand {
    return if (json.openshiftKind == "route") {

        val payload = json.deepCopy<JsonNode>()

        val spec = payload.at("/spec") as ObjectNode
        spec.set("host", TextNode("yoda"))

        OpenshiftCommand(OperationType.UPDATE, payload, json)
    } else {
        OpenshiftCommand(OperationType.CREATE, json)
    }
}