package no.skatteetaten.aurora.boober.utils

import org.springframework.boot.convert.DurationStyle
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.parser.CronParser
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.BooleanNode
import com.fasterxml.jackson.databind.node.MissingNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.module.kotlin.treeToValue
import org.springframework.boot.convert.DurationStyle
import java.net.URI

inline fun <reified T : Any> JsonNode.convert(): T = jacksonObjectMapper().convertValue(this)

fun JsonNode.findAllPointers(maxLevel: Int): List<String> {

    fun inner(root: String, node: ObjectNode): List<String> {

        if (root.startsWith("/mount") && root.split("/").size > maxLevel) {
            return listOf(root)
        }

        if (root.startsWith("/secretVault") && root.contains("keyMappings")) {
            return listOf()
        }

        val ret = mutableListOf<String>()
        for ((key, child) in node.fields()) {
            val newKey = "$root/$key"
            if (child is ObjectNode) {
                ret += inner(newKey, child)
            } else {
                ret += newKey
            }
        }
        return ret
    }

    if (this is ObjectNode) {
        return inner("", this)
    } else {
        return listOf()
    }
}

fun JsonNode.getBoolean(nodeName: String): Boolean {
    val valueNode = this[nodeName]
    return when (valueNode) {
        is BooleanNode -> valueNode.booleanValue()
        is TextNode -> valueNode.textValue() == "true"
        else -> false
    }
}

fun JsonNode.atNullable(path: String): JsonNode? {
    val value = this.at(path.ensureStartWith("/"))
    if (value.isMissingNode) {
        return null
    }
    return value
}

fun List<JsonNode>.deploymentConfig(): JsonNode? = this.find { it.openshiftKind == "deploymentconfig" }
fun List<JsonNode>.imageStream(): JsonNode? = this.find { it.openshiftKind == "imagestream" }

fun JsonNode.annotation(name: String): String? {
    val annotations = this.at("/metadata/annotations") ?: return null
    if (annotations is MissingNode) {
        return null
    }

    val entries = jacksonObjectMapper().treeToValue<Map<String, String>>(annotations) ?: return null
    return entries[name]
}

val JsonNode.namespace: String
    get() = this.get("metadata")?.get("namespace")?.asText() ?: ""

val JsonNode.apiVersion: String
    get() = this.get("apiVersion").asText()

val JsonNode.apiPrefix: String
    get() = findOpenShiftApiPrefix(this.apiVersion, this.openshiftKind)

val JsonNode.apiBaseUrl: String
    get() = "/${this.apiPrefix}/${this.apiVersion}"

val JsonNode.resourceUrl: String
    get() = "${this.apiBaseUrl}/${this.openshiftKind}s"

val JsonNode.namedUrl: String
    get() = "${this.resourceUrl}/${this.openshiftName}"

val JsonNode.namespacedResourceUrl: String
    get() = "${this.apiBaseUrl}/namespaces/${this.namespace}/${this.openshiftKind}s"

val JsonNode.namespacedNamedUrl: String
    get() = "${this.namespacedResourceUrl}/${this.openshiftName}"

val JsonNode.appropriateResourceAndNamedUrl get() = if (this.namespace.isEmpty()) {
    this.resourceUrl to this.namedUrl
} else {
    this.namespacedResourceUrl to this.namespacedNamedUrl
}

val JsonNode.appropriateNamedUrl: String get() = appropriateResourceAndNamedUrl.second

val JsonNode.openshiftKind: String
    get() = this.get("kind")?.asText()?.toLowerCase()
        ?: throw IllegalArgumentException("Kind must be set in file=$this")

val JsonNode.openshiftName: String
    get() = when (this.openshiftKind) {
        "deploymentrequest" -> this.get("name")?.asText()
            ?: throw IllegalArgumentException("name not specified for resource kind=${this.openshiftKind}")
        else -> this.get("metadata")?.get("name")?.asText()
            ?: this.get("metadata")?.get("generateName")?.asText()
            ?: throw IllegalArgumentException("name not specified for resource kind=${this.openshiftKind}")
    }

fun JsonNode.updateField(source: JsonNode, root: String, field: String, required: Boolean = false) {
    val sourceField = source.at("$root/$field")

    if (sourceField.isMissingNode) {
        if (required) {
            throw IllegalArgumentException("Field $root/$field is not set in source")
        }
        return
    }

    val targetRoot = this.at(root)
    if (targetRoot.isMissingNode) {
        if (required) {
            throw IllegalArgumentException("Root $root is not set in target")
        } else {
            return
        }
    }

    (targetRoot as ObjectNode).replace(field, sourceField)
}

fun JsonNode.mergeField(source: ObjectNode, root: String, field: String) {
    val jsonPtrExpr = "$root/$field"
    val sourceObject = source.at(jsonPtrExpr)
    if (sourceObject.isMissingNode) {
        return
    }
    val mergedObject = (sourceObject as ObjectNode).deepCopy()

    val fieldNode = this.at(jsonPtrExpr)
    if (fieldNode is ObjectNode) {
        mergedObject.setAll<ObjectNode>(fieldNode)
    }

    val rootNode = this.at(root) as ObjectNode
    rootNode.replace(field, mergedObject)
}

fun JsonNode?.startsWith(pattern: String, message: String): Exception? {
    if (this == null || !this.isTextual) {
        return IllegalArgumentException(message)
    }
    if (!this.textValue().startsWith(pattern)) {
        return IllegalArgumentException(message)
    }

    return null
}

fun JsonNode?.pattern(pattern: String, message: String, required: Boolean = true): Exception? {
    if (this == null || !this.isTextual) {
        return if (required) {
            IllegalArgumentException(message)
        } else {
            null
        }
    }
    if (!Regex(pattern).matches(this.textValue())) {
        return IllegalArgumentException(message)
    }

    return null
}

fun JsonNode?.durationString(): Exception? {
    if (this == null || !this.isTextual) {
        return null
    }

    DurationStyle.SIMPLE.parse(this.textValue())
    return null
}

fun JsonNode?.validUnixCron(): Exception? {
    if (this == null) return IllegalArgumentException("Cron schedule is required")
    val parser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))
    return try {
        parser.parse(this.textValue())
        null
    } catch (e: Exception) {
        e
    }
}

fun JsonNode?.int(required: Boolean = false): Exception? {
    if (this == null) {
        return if (required) {
            IllegalArgumentException("Required boolean value is not set.")
        } else {
            null
        }
    }
    if (!this.isInt) {
        return IllegalArgumentException("Not a valid int value")
    }
    return null
}

fun JsonNode?.boolean(required: Boolean = false): Exception? {
    val candidates = listOf("true", "false")

    if (this == null) {
        return if (required) {
            IllegalArgumentException("Required boolean value is not set.")
        } else {
            null
        }
    }
    if (this.isBoolean) {
        return null
    }

    if (!candidates.contains(this.textValue().toLowerCase())) {
        return IllegalArgumentException("Not a valid boolean value.")
    }
    return null
}

fun JsonNode?.oneOf(candidates: List<String>, required: Boolean = true): Exception? {
    if (this == null || !this.isTextual) {
        return if (required) {
            IllegalArgumentException("Must be one of [" + candidates.joinToString() + "]")
        } else {
            null
        }
    }
    if (!candidates.contains(this.textValue())) {
        return IllegalArgumentException("Must be one of [" + candidates.joinToString() + "]")
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
    if (this == null || !this.isTextual || this.textValue().isBlank()) {
        return IllegalArgumentException(message)
    }

    return null
}

fun JsonNode?.validUrl(required: Boolean = true): Exception? {
    if (this == null || !this.isTextual || this.textValue().isBlank()) {
        return if (required) {
            return IllegalArgumentException("Need to set a valid URL.")
        } else {
            null
        }
    }
    // Create will throw IllegalArgumentException if not valid URI
    URI.create(this.textValue())
    return null
}

fun JsonNode?.length(length: Int, message: String, required: Boolean = true): Exception? {
    if (this == null || !this.isTextual) {
        return if (required) {
            IllegalArgumentException(message)
        } else {
            null
        }
    } else if (this.textValue().length > length) {
        return IllegalArgumentException(message)
    }

    return null
}

fun jacksonYamlObjectMapper(): ObjectMapper =
    ObjectMapper(YAMLFactory().enable(JsonParser.Feature.ALLOW_COMMENTS)).registerKotlinModule()

fun jsonMapper(): ObjectMapper = jacksonObjectMapper().enable(JsonParser.Feature.ALLOW_COMMENTS)
