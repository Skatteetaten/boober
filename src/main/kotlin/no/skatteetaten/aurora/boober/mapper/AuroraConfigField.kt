package no.skatteetaten.aurora.boober.mapper

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.MissingNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraSecretVault
import no.skatteetaten.aurora.boober.model.Database
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType
import no.skatteetaten.aurora.boober.utils.nullOnEmpty
import org.apache.commons.lang.StringEscapeUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory


data class AuroraConfigField(val handler: AuroraConfigFieldHandler, val source: AuroraConfigFile? = null) {
    val value: JsonNode
        get() = source?.contents?.at(handler.path) ?: MissingNode.getInstance()
    val valueOrDefault: JsonNode?
        get() =
            value.let {
                if (it.isMissingNode) {
                    jacksonObjectMapper().convertValue(handler.defaultValue, JsonNode::class.java)
                } else {
                    it
                }
            }
}

inline fun <reified T> AuroraConfigField.getNullableValue(): T? {

    if (this.source == null) {
        return this.handler.defaultValue as T
    }

    val value = this.value

    return jacksonObjectMapper().convertValue(value, T::class.java)
}

inline fun <reified T> AuroraConfigField.value(): T {

    if (this.source == null) {
        return this.handler.defaultValue as T
    }

    val value = this.source.contents.at(this.handler.path)

    return jacksonObjectMapper().convertValue(value, T::class.java)

}

class AuroraConfigFields(val fields: Map<String, AuroraConfigField>) {


    fun getMounts(extractors: List<AuroraConfigFieldHandler>, vaults: Map<String, AuroraSecretVault>): List<Mount>? {
        if (extractors.isEmpty()) {
            return null
        }

        val mountNames = extractors.map {
            val (_, name, _) = it.name.split("/", limit = 3)
            name
        }.toSet()

        return mountNames.map { mount ->
            val type: MountType = extract("mounts/$mount/type")

            val permissions = if (type == MountType.Secret) {
                extractOrNull<String?>("mounts/$mount/secretVault")?.let {
                    vaults[it]?.permissions
                }
            } else null

            val content = if (type == MountType.ConfigMap) {
                extract("mounts/$mount/content")
            } else {
                extractOrNull<String?>("mounts/$mount/secretVault")?.let {
                    vaults[it]?.secrets
                }
            }

            Mount(
                    extract("mounts/$mount/path"),
                    type,
                    extract("mounts/$mount/mountName"),
                    extract("mounts/$mount/volumeName"),
                    extract("mounts/$mount/exist"),
                    content,
                    permissions
            )
        }
    }


    fun getConfigMap(configExtractors: List<AuroraConfigFieldHandler>): Map<String, Any?>? {

        val envMap: Map<String, Any?> = configExtractors.filter { it.name.count { it == '/' } == 1 }.map {
            val (_, field) = it.name.split("/", limit = 2)
            val value: Any = extract(it.name)
            val escapedValue = if (value is String) StringEscapeUtils.escapeJavaScript(value) else value
            field to escapedValue
        }.toMap()


        val configMap: MutableMap<String, MutableMap<String, Any?>> = mutableMapOf()
        configExtractors.filter { it.name.count { it == '/' } > 1 }.forEach {

            val parts = it.name.split("/", limit = 3)

            val (_, configFile, field) = parts

            val value: Any = extract(it.name)
            val escapedValue = if (value is String) StringEscapeUtils.escapeJavaScript(value) else value
            val keyValue = mutableMapOf(field to escapedValue)

            val keyProps = if (!configFile.endsWith(".properties")) {
                "$configFile.properties"
            } else configFile

            if (configMap.containsKey(keyProps)) configMap[keyProps]?.putAll(keyValue)
            else configMap.put(keyProps, keyValue)
        }

        val propertiesMap: Map<String, String> = configMap.map { (key, value) ->
            key to value.map {
                "${it.key}=${it.value}"
            }.joinToString(separator = "\\n")
        }.toMap()

        if (envMap.isEmpty()) {
            return propertiesMap.nullOnEmpty()
        }

        //TODO: When we have 3.6 we can remove this
        val latestPair: Pair<String, String> =
                "latest.properties" to envMap.map {
                    "${it.key}=${it.value}"
                }.joinToString(separator = "\\n")


        val config = propertiesMap + envMap + latestPair
        return config.nullOnEmpty()

    }

    fun getRouteAnnotations(prefix: String, extractors: List<AuroraConfigFieldHandler>): Map<String, String> {
        return extractors
                .filter { it.path.startsWith("/$prefix") }
                .map {
                    val (_, _, _, field) = it.name.split("/", limit = 4)

                    val value: String = extract(it.name)
                    field to value
                }.toMap()
    }

    fun getDatabases(extractors: List<AuroraConfigFieldHandler>): List<Database> {

        return extractors.map {
            val (_, field) = it.name.split("/", limit = 2)

            val value: String = extract(it.name)
            Database(field, if (value == "auto" || value.isBlank()) null else value)
        }
    }


    fun getParameters(parameterExtractors: List<AuroraConfigFieldHandler>): Map<String, String>? {
        return parameterExtractors.map {
            val (_, field) = it.name.split("/", limit = 2)

            val value: String = extract(it.name)
            field to value
        }.toMap()
    }

    fun disabledAndNoSubKeys(name: String): Boolean {

        val simplified = isSimplifiedConfig(name)

        return simplified && !extract<Boolean>(name)

    }

    fun isSimplifiedConfig(name: String): Boolean {
        val field = fields[name]!!

        if (field.source == null) {
            return field.handler.defaultValue is Boolean
        }
        val value = field.source.contents.at(field.handler.path)

        if (value.isBoolean) {
            return true
        }

        return false
    }

    inline fun <reified T> extract(name: String): T = fields[name]!!.value()

    inline fun <reified T> extractOrNull(name: String): T? = fields[name]!!.getNullableValue()


    companion object {

        val logger: Logger = LoggerFactory.getLogger(AuroraConfigFields::class.java)

        fun create(handlers: Set<AuroraConfigFieldHandler>, files: List<AuroraConfigFile>): AuroraConfigFields {
            val fields: Map<String, AuroraConfigField> = handlers.map { handler ->
                val matches = files.reversed().mapNotNull {
                    logger.trace("Check if  ${handler.path} exist in file  ${it.contents}")
                    val value = it.contents.at(handler.path)

                    if (!value.isMissingNode) {
                        logger.trace("Match $value i fil ${it.configName}")
                        AuroraConfigField(handler, it)
                    } else null
                }

                matches.firstOrNull()?.let {
                    it
                } ?: AuroraConfigField(handler)

            }.associate { it.handler.name to it }

            return AuroraConfigFields(fields)
        }

    }
}
