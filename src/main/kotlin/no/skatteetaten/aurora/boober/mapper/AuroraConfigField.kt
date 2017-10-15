package no.skatteetaten.aurora.boober.mapper

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.convertValue
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

data class AuroraConfigField(val path: String, val value: JsonNode, val source: String)


class AuroraConfigFields(val fields: Map<String, AuroraConfigField>) {


    fun getMounts(extractors: List<AuroraConfigFieldHandler>, vaults: Map<String, AuroraSecretVault>): List<Mount>? {
        if (extractors.isEmpty()) {
            return null
        }

        val mountNames = extractors.map {
            val (_, name, _) = it.name.split("/", limit = 3)
            name
        }.toSet()

        return mountNames.map {
            val type = extract("mounts/$it/type", { MountType.valueOf(it.asText()) })

            val permissions = if (type == MountType.Secret) {
                extractOrNull("mounts/$it/secretVault", {
                    vaults[it.asText()]?.permissions
                })
            } else null

            val content = if (type == MountType.ConfigMap) {
                extractOrNull("mounts/$it/content", { jacksonObjectMapper().convertValue<Map<String, String>>(it) })
            } else {
                extractOrNull("mounts/$it/secretVault", {
                    vaults[it.asText()]?.secrets
                })
            }

            Mount(
                    extract("mounts/$it/path"),
                    type,
                    extract("mounts/$it/mountName"),
                    extract("mounts/$it/volumeName"),
                    extract("mounts/$it/exist", { it.asText() == "true" }),
                    content,
                    permissions
            )
        }
    }


    fun getConfigMap(configExtractors: List<AuroraConfigFieldHandler>): Map<String, String>? {


        val envMap: Map<String, String> = configExtractors.filter { it.name.count { it == '/' } == 1 }.map {
            val (_, field) = it.name.split("/", limit = 2)
            val value = extract(it.name)
            field to StringEscapeUtils.escapeJavaScript(value)
        }.toMap()


        val configMap: MutableMap<String, MutableMap<String, String>> = mutableMapOf()
        configExtractors.filter { it.name.count { it == '/' } > 1 }.forEach {

            val parts = it.name.split("/", limit = 3)

            val (_, configFile, field) = parts

            val value = StringEscapeUtils.escapeJavaScript(extract(it.name))
            val keyValue = mutableMapOf(field to value)

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

                    val value = extract(it.name)
                    field to value
                }.toMap()
    }

    fun getDatabases(extractors: List<AuroraConfigFieldHandler>): List<Database> {

        return extractors.map {
            val (_, field) = it.name.split("/", limit = 2)

            val value = extract(it.name)
            Database(field, if (value == "auto" || value.isBlank()) null else value)
        }
    }


    fun getParameters(parameterExtractors: List<AuroraConfigFieldHandler>): Map<String, String>? {
        return parameterExtractors.map {
            val (_, field) = it.name.split("/", limit = 2)

            val value = extract(it.name)
            field to value
        }.toMap()
    }


    fun <T> findAll(name: String, mapper: (Map<String, AuroraConfigField>) -> T): T? {

        val fields = fields.entries.filter { it.key.contains(name) }.map { it.key to it.value }.toMap()

        if (fields.isEmpty()) return null

        return mapper(fields)
    }


    fun extractOrNull(name: String): String? {
        return if (fields.containsKey(name)) extract(name)
        else null
    }

    fun <T> extractOrNull(name: String, mapper: (JsonNode) -> T): T? {
        return if (fields.containsKey(name)) extract(name, mapper)
        else null
    }

    inline fun <reified T> extractOrDefault(name: String, default: T): T {
        return if (fields.containsKey(name)) jacksonObjectMapper().convertValue(fields[name]!!.value, T::class.java)
        else default
    }

    fun extract(name: String): String {
        return extract<String>(name, JsonNode::textValue)
    }

    fun <T> extract(name: String, mapper: (JsonNode) -> T): T {

        if (!fields.containsKey(name)) throw IllegalArgumentException("$name is not set")

        return mapper(fields[name]!!.value)
    }

    companion object {

        val logger: Logger = LoggerFactory.getLogger(AuroraConfigFields::class.java)
        fun create(handlers: Set<AuroraConfigFieldHandler>, files: List<AuroraConfigFile>): AuroraConfigFields {
            val fields = handlers.mapNotNull { handler ->

                val matches = files.reversed().mapNotNull {
                    logger.debug("Check if  ${handler.path} exist in file  ${it.contents}")
                    val value = it.contents.at(handler.path)

                    if (!value.isMissingNode) {
                        logger.debug("Match $value i fil ${it.configName}")
                        handler.name to AuroraConfigField(handler.path, value, it.configName)
                    } else null
                }

                when {
                    (matches.isEmpty() && handler.defaultValue != null) -> {
                        logger.debug("Default match ${handler.defaultValue}")
                        handler.name to AuroraConfigField(handler.path, TextNode(handler.defaultValue), "default")
                    }
                    matches.isNotEmpty() -> matches.first()
                    else -> null
                }
            }.toMap()

            return AuroraConfigFields(fields)
        }
    }
}
