package no.skatteetaten.aurora.boober.mapper.v1

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraVolume
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType
import no.skatteetaten.aurora.boober.utils.ensureEndsWith
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
                config = getApplicationConfigFiles(auroraConfigFields),
                mounts = getMounts(auroraConfigFields))
    }

    private fun createSecretVaultHandlers(): List<AuroraConfigFieldHandler> {
        return listOf(
                AuroraConfigFieldHandler("secretVault"),
                AuroraConfigFieldHandler("secretVault/name"),
                AuroraConfigFieldHandler("secretVault/keys")
        )
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


    private fun getApplicationConfigFiles(auroraConfigFields: AuroraConfigFields): Map<String, String>? {

        data class ConfigFieldValue(val fileName: String, val field: String, val escapedValue: String)

        fun extractConfigFieldValues(): List<ConfigFieldValue> {
            return configHandlers
                    .map { it.name }
                    .filter { it.count { it == '/' } > 1 }
                    .map { name ->
                        val value: Any = auroraConfigFields.extract(name)
                        val escapedValue: String = convertValueToString(value)
                        val (_, configFile, field) = name.split("/", limit = 3)
                        val fileName = configFile.ensureEndsWith(".properties")
                        ConfigFieldValue(fileName, field, escapedValue)
                    }
        }

        val configFieldValues = extractConfigFieldValues()
        if (configFieldValues.isEmpty()) {
            return null
        }

        val configFileIndex: MutableMap<String, MutableMap<String, String>> = mutableMapOf()
        configFieldValues.forEach {
            configFileIndex.getOrPut(it.fileName, { mutableMapOf() })[it.field] = it.escapedValue
        }

        fun Map<String, String>.toPropertiesFile(): String = this
                .map { "${it.key}=${it.value}" }
                .joinToString(separator = System.getProperty("line.separator"))
        return configFileIndex.map { it.key to it.value.toPropertiesFile() }.toMap()
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