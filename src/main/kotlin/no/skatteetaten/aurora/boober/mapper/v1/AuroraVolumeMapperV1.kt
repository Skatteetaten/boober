package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraVolume
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.required
import org.apache.commons.lang.StringEscapeUtils

class AuroraVolumeMapperV1(private val applicationFiles: List<AuroraConfigFile>) {

    private val mountHandlers = createMountHandlers()
    val configHandlers = applicationFiles.findConfigFieldHandlers()
    private val secretVaultHandlers = createSecretVaultHandlers()

    val handlers = configHandlers + mountHandlers + secretVaultHandlers

    fun createAuroraVolume(auroraConfigFields: AuroraConfigFields): AuroraVolume {

        return AuroraVolume(
                secretVaultName = getSecretVault(auroraConfigFields),
                secretVaultKeys = getSecretVaultKeys(auroraConfigFields),
                config = getConfigMap(auroraConfigFields),
                mounts = getMounts(auroraConfigFields))
    }

    private fun createSecretVaultHandlers(): List<AuroraConfigFieldHandler> {
        val keyName = "secretVault"
        val secretVaultSubKeysHandlers = applicationFiles.findSubKeys(keyName)
                .map { AuroraConfigFieldHandler("$keyName/$it") }

        return secretVaultSubKeysHandlers + AuroraConfigFieldHandler(keyName)
    }

    private fun createMountHandlers(): List<AuroraConfigFieldHandler> {

        val mountKeys = applicationFiles.findSubKeys("mounts")

        return mountKeys.flatMap { mountName ->
            listOf(
                    AuroraConfigFieldHandler("mounts/$mountName/path", validator = { it.required("Path is required for mount") }),
                    AuroraConfigFieldHandler("mounts/$mountName/type", validator = { it.oneOf(MountType.values().map { it.name }) }),
                    AuroraConfigFieldHandler("mounts/$mountName/mountName", defaultValue = mountName),
                    AuroraConfigFieldHandler("mounts/$mountName/volumeName", defaultValue = mountName),
                    AuroraConfigFieldHandler("mounts/$mountName/exist", defaultValue = false),
                    AuroraConfigFieldHandler("mounts/$mountName/content"),
                    AuroraConfigFieldHandler("mounts/$mountName/secretVault")
            )
        }
    }


    private fun getConfigMap(auroraConfigFields: AuroraConfigFields): Map<String, Any?>? {

        val configMap: MutableMap<String, MutableMap<String, Any?>> = mutableMapOf()
        configHandlers.filter { it.name.count { it == '/' } > 1 }.forEach {

            val parts = it.name.split("/", limit = 3)

            val (_, configFile, field) = parts

            val value: Any = auroraConfigFields.extract(it.name)
            val escapedValue = if (value is String) StringEscapeUtils.escapeJavaScript(value) else value
            val keyValue = mutableMapOf(field to escapedValue)

            val keyProps = if (!configFile.endsWith(".properties")) {
                "$configFile.properties"
            } else configFile

            if (configMap.containsKey(keyProps)) configMap[keyProps]?.putAll(keyValue)
            else configMap.put(keyProps, keyValue)
        }

        if (configMap.isEmpty()) {
            return null
        }

        return configMap.map { (key, value) ->
            key to value.map {
                "${it.key}=${it.value}"
            }.joinToString(separator = "\\n")
        }.toMap()
    }

    private fun getSecretVault(auroraConfigFields: AuroraConfigFields): String? =
            auroraConfigFields.extractIfExistsOrNull("secretVault/name")
                    ?: auroraConfigFields.extractIfExistsOrNull("secretVault")


    private fun getSecretVaultKeys(auroraConfigFields: AuroraConfigFields): List<String> =
            auroraConfigFields.extractIfExistsOrNull("secretVault/keys") ?: listOf()


    private fun getMounts(auroraConfigFields: AuroraConfigFields): List<Mount>? {
        if (mountHandlers.isEmpty()) {
            return null
        }

        val mountNames = mountHandlers.map {
            val (_, name, _) = it.name.split("/", limit = 3)
            name
        }.toSet()

        return mountNames.map { mount ->
            val type: MountType = auroraConfigFields.extract("mounts/$mount/type")

            val content: Map<String, String>? = if (type == MountType.ConfigMap) {
                auroraConfigFields.extract("mounts/$mount/content")
            } else {
                null
            }
            val secretVaultName = auroraConfigFields.extractOrNull<String?>("mounts/$mount/secretVault")
            Mount(
                    auroraConfigFields.extract("mounts/$mount/path"),
                    type,
                    auroraConfigFields.extract("mounts/$mount/mountName"),
                    auroraConfigFields.extract("mounts/$mount/volumeName"),
                    auroraConfigFields.extract("mounts/$mount/exist"),
                    content,
                    secretVaultName
            )
        }
    }
}