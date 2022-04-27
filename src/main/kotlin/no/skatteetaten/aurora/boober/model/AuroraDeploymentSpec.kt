package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.utils.atNullable
import no.skatteetaten.aurora.boober.utils.convertValueToString
import no.skatteetaten.aurora.boober.utils.toMultiMap
import org.apache.commons.text.StringSubstitutor

data class AuroraDeploymentSpec(
    val fields: Map<String, AuroraConfigField>,
    val replacer: StringSubstitutor
) {

    fun findSubKeys(name: String): Set<String> {
        val prefix = if (!name.endsWith("/")) {
            "$name/"
        } else name

        return fields.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore("/") }
            .toSet()
    }

    inline fun <reified T> associateSubKeys(
        name: String
    ): Map<String, T> {
        return this.findSubKeys(name).associateWith {
            this.get<T>("$name/$it")
        }
    }

    fun getConfigEnv(configExtractors: List<AuroraConfigFieldHandler>): Map<String, String> {
        return configExtractors.filter { it.name.count { it == '/' } == 1 }.associate {
            val (_, field) = it.name.split("/", limit = 2)
            val value: Any = this[it.name]
            val escapedValue: String = convertValueToString(value)
            field to escapedValue
        }
    }

    fun getRouteAnnotations(prefix: String): Map<String, String> {
        return fields.keys
            .filter { it.startsWith(prefix) }
            .associate {
                val field = it.removePrefix(prefix)
                val value: Any = this[it]
                val escapedValue: String = convertValueToString(value)
                field to escapedValue
            }.filter {
                it.value.isNotBlank()
            }
    }

    fun getParameters(): Map<String, String> {
        return fields.keys
            .filter { it.startsWith("parameters/") }
            .associate {
                val field = it.removePrefix("parameters/")
                val value: String = this[it]
                field to value
            }
    }

    fun getKeyMappings(keyMappingsExtractor: AuroraConfigFieldHandler?): Map<String, String>? =
        keyMappingsExtractor?.let { getOrNull(it.name) }

    fun <T> featureEnabled(name: String, fn: (String) -> T?): T? {

        // feature is disabled and has no specified subKeys
        // adding a sub key implicitly enables a feature
        if (isSimplifiedAndDisabled(name)) {
            return null
        }

        return fn(name)
    }

    fun isSimplifiedAndDisabled(name: String): Boolean {
        return isSimplifiedConfig(name) && !get<Boolean>(name)
    }

    fun isSimplifiedAndEnabled(name: String): Boolean {
        return isSimplifiedConfig(name) && get(name)
    }

    /*
    In order to know if this is simplified config or not we need to find out what instruction is
    specified in the most specific place. Each AuroraConfigFieldSource has a precedence according to the
    AuroraConfigFileType enum.
     */
    fun isSimplifiedConfig(name: String): Boolean {
        val field = fields[name]!!

        // if a field is not marked as simplified it will not be simplified
        if (!field.canBeSimplified) return false

        val subKeys = getSubKeys(name)
        // If there are no subkeys we cannot be complex
        if (subKeys.isEmpty()) {
            return true
        }

        // if any subkey has higher presedence accoring to the AuroraConfigFileTypeEnum we are not simplified
        return !subKeys.any { it.value.fileType > field.fileType }
    }

    fun hasSubKeys(name: String): Boolean = getSubKeys(name).isNotEmpty()

    // Note that we cannot replace the keys here.
    fun findSubKeysRaw(name: String): Set<String> {
        return fields.filter { it.key.startsWith("$name/") }.keys.map { it.split("/")[1] }.toSet()
    }

    fun getSubKeys(name: String): Map<String, AuroraConfigField> {
        return fields
            .filter { it.key.startsWith("$name/") }
            .mapKeys { replacer.replace(it.key) }
    }

    fun getSubKeyValues(name: String): List<String> {
        val amountOfSubKeysInName = name.split("/").size
        return getSubKeys(name).keys.map {
            it.split("/")[amountOfSubKeysInName]
        }.distinct()
    }

    inline operator fun <reified T> get(name: String): T = fields[name]!!.value()

    inline fun <reified T> getOrDefault(
        root: String,
        index: String,
        suffix: String,
        defaultRoot: String = "${root}Defaults"
    ): T {
        return getOrNull("$root/$index/$suffix")
            ?: get("$defaultRoot/$suffix")
    }

    inline fun <reified T> getOrDefaultElseNull(
        root: String,
        index: String,
        suffix: String,
        defaultRoot: String = "${root}Defaults"
    ): T? {
        return getOrNull("$root/$index/$suffix")
            ?: getOrNull("$defaultRoot/$suffix")
    }

    /**
     * Extracts a config field declared either as a delimited string (ie. "value1, value2") or as a JSON array
     * (ie. ["value1", "value2"]) as a String list.
     */
    fun getDelimitedStringOrArrayAsSet(name: String, delimiter: String = ","): Set<String> {
        return getDelimitedStringOrArrayAsSetOrNull(name, delimiter) ?: emptySet()
    }

    /**
     * Extracts a config field declared either as a delimited string (ie. "value1, value2") or as a JSON array
     * (ie. ["value1", "value2"]) as a String list.
     */
    fun getDelimitedStringOrArrayAsSetOrNull(name: String, delimiter: String = ","): Set<String>? {
        return fields[name]?.extractDelimitedStringOrArrayAsSet(delimiter)
    }

    inline fun <reified T> getOrNull(name: String): T? = fields[name]?.getNullableValue()

    companion object {
        fun createHeader(
            handlers: Set<AuroraConfigFieldHandler>,
            files: List<AuroraConfigFile>,
            applicationDeploymentRef: ApplicationDeploymentRef,
            auroraConfigVersion: String,
        ): AuroraDeploymentSpec {
            return createBaseSpec(
                handlers = handlers,
                files = files,
                applicationDeploymentRef = applicationDeploymentRef,
                auroraConfigVersion = auroraConfigVersion
            )
        }
        fun createComplete(
            handlers: Set<AuroraConfigFieldHandler>,
            files: List<AuroraConfigFile>,
            applicationDeploymentRef: ApplicationDeploymentRef,
            auroraConfigVersion: String,
            replacer: StringSubstitutor,
            namespace: String,
            applicationDeploymentId: String
        ): AuroraDeploymentSpec {
            val additionalFields = mapOf(
                "namespace" to namespace,
                "applicationDeploymentId" to applicationDeploymentId
            )

            return createBaseSpec(
                handlers = handlers,
                files = files,
                applicationDeploymentRef = applicationDeploymentRef,
                auroraConfigVersion = auroraConfigVersion,
                replacer = replacer,
                additionalFields = additionalFields
            )
        }

        private fun createBaseSpec(
            handlers: Set<AuroraConfigFieldHandler>,
            files: List<AuroraConfigFile>,
            applicationDeploymentRef: ApplicationDeploymentRef,
            replacer: StringSubstitutor = StringSubstitutor(),
            auroraConfigVersion: String,
            additionalFields: Map<String, String> = emptyMap()
        ): AuroraDeploymentSpec {

            val mapper = jacksonObjectMapper()

            val staticFields: List<Pair<String, AuroraConfigFieldSource>> =
                listOf(
                    "applicationDeploymentRef" to
                        AuroraConfigFieldSource(
                            AuroraConfigFile("static", "{}", isDefault = true),
                            mapper.convertValue(applicationDeploymentRef.toString())
                        ),
                    "configVersion" to
                        AuroraConfigFieldSource(
                            AuroraConfigFile("static", "{}", isDefault = true),
                            mapper.convertValue(auroraConfigVersion)
                        ),
                )

            val additionalStaticFields = additionalFields.map { (name, value) ->
                name to AuroraConfigFieldSource(
                    AuroraConfigFile("static", "{}", isDefault = true),
                    mapper.convertValue(value)
                )
            }

            val fields: List<Pair<String, AuroraConfigFieldSource>> = handlers.flatMap { handler ->
                val defaultValue = handler.defaultValue?.let {
                    listOf(
                        handler.name to AuroraConfigFieldSource(
                            configFile = AuroraConfigFile(
                                name = handler.defaultSource,
                                contents = "",
                                isDefault = true
                            ),
                            canBeSimplified = handler.canBeSimplifiedConfig,
                            value = mapper.convertValue(handler.defaultValue)
                        )
                    )
                } ?: emptyList()

                val result = defaultValue + files.mapNotNull { file ->
                    file.asJsonNode.atNullable(handler.name)?.let {
                        /*
                          If a handler can be simplified or complex we do not create
                          a source if it is complex

                          The indidividual subKeys have their own handlers.
                         */
                        if (handler.canBeSimplifiedConfig && it.isObject) {
                            null
                        } else {
                            handler.name to AuroraConfigFieldSource(
                                configFile = file,
                                value = it,
                                canBeSimplified = handler.canBeSimplifiedConfig
                            )
                        }
                    }
                }
                result
            }

            val allFields: List<Pair<String, AuroraConfigFieldSource>> =
                staticFields + additionalStaticFields + fields

            val groupedFields: Map<String, AuroraConfigField> = allFields
                .toMultiMap()
                .mapValues { AuroraConfigField(it.value.toSet(), replacer) }

            return AuroraDeploymentSpec(
                fields = groupedFields,
                replacer = replacer
            )
        }
    }
}
