package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.model.AuroraConfigField
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile


/**
 * Creates a copy of node1 and copies (and potentially overwriting) all properties from node2 into it.
 * @param node1
 * @param node2
 */
fun createMergeCopy(node1: Map<String, Any?>, node2: Map<String, Any?>): Map<String, Any?> {
    val mergeTarget: MutableMap<String, Any?> = HashMap(node1)
    return copyJsonProperties(mergeTarget, node2)
}

fun copyJsonProperties(targetNode: MutableMap<String, Any?>, sourceNode: Map<String, Any?>): Map<String, Any?> {
    sourceNode.forEach { (key, value) ->
        when (value) {
            is Map<*, *> -> {
                val targetChildNode = copyChildNode(key, targetNode)

                if (targetChildNode != null) {
                    targetChildNode.putAll(value as Map<String, Any?>)
                    targetNode.replace(key, targetChildNode)
                } else {
                    targetNode.put(key, value)
                }
            }
            else -> {
                targetNode.put(key, value)
            }
        }
    }

    return targetNode
}


private fun copyChildNode(key: String, targetNode: MutableMap<String, Any?>): HashMap<String, Any?>? {
    return if (targetNode.containsKey(key)) HashMap(targetNode[key] as MutableMap<String, Any?>)
    else null
}

fun JsonNode.updateField(source: JsonNode, root: String, field: String, required: Boolean = false) {
    val sourceField = source.at("$root/$field")

    if (sourceField.isMissingNode) {
        if (required) {
            throw IllegalArgumentException("Field $root/$field is not set in source")
        }
        return
    }

    val targetRoot = this.at(root) as ObjectNode
    if (targetRoot.isMissingNode) {
        throw IllegalArgumentException("Root $root is not set in target")
    }

    targetRoot.set(field, sourceField)
}

fun JsonNode?.pattern(pattern: String, message: String): Exception? {
    if (this == null) {
        return IllegalArgumentException(message)
    }
    if (!Regex(pattern).matches(this.textValue())) {
        return IllegalArgumentException(message)

    }

    return null
}

fun JsonNode?.required(message: String): Exception? {
    if (this == null) {
        return IllegalArgumentException(message)
    }
    return null
}

fun JsonNode?.notBlank(message: String): Exception? {
    if (this == null || this.textValue().isBlank()) {
        return IllegalArgumentException(message)
    }

    return null
}

fun JsonNode?.length(length: Int, message: String): Exception? {
    if (this == null) {
        return IllegalArgumentException(message)
    } else if (this.textValue().length > length) {
        return IllegalArgumentException(message)
    }

    return null
}


fun Map<String, AuroraConfigField>.extract(name: String): String {
    return this.extract<String>(name, JsonNode::textValue)
}

fun <T> Map<String, AuroraConfigField>.extract(name: String, mapper: (JsonNode) -> T): T {

    if (!this.containsKey(name)) throw IllegalArgumentException("$name is not set")

    return mapper(this.get(name)!!.value)
}

fun List<AuroraConfigFile>.findConfigExtractors(): List<AuroraConfigFieldHandler> {

    //find all config fieldNames in all files
    val configFiles = this.flatMap {
        it.contents.fieldNames().asSequence().toList()
    }.toSet()


    val configKeys: Map<String, Set<String>> = configFiles.map { configFileName ->
        //find all unique keys in a configFile
        val configKeys = this.flatMap { ac ->
            ac.contents.get(configFileName)?.fieldNames()?.asSequence()?.toList() ?: emptyList()
        }.toSet()

        configFileName to configKeys
    }.toMap()

    return configKeys.map {
        AuroraConfigFieldHandler("config/${it.key}/${it.value}", "/config/$it/${it.value}")
    }
}
