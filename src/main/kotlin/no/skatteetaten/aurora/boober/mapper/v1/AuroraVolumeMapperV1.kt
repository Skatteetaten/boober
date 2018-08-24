package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraVolume
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType
import no.skatteetaten.aurora.boober.utils.ensureEndsWith
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.required

class AuroraVolumeMapperV1(private val applicationFiles: List<AuroraConfigFile>) {

    private val mountHandlers = createMountHandlers()
    val configHandlers = applicationFiles.findConfigFieldHandlers()
    private val secretVaultHandlers = createSecretVaultHandlers()
    private val secretVaultKeyMappingHandler = createSecretVaultKeyMappingHandler()

    val handlers = configHandlers + mountHandlers + secretVaultHandlers + listOfNotNull(secretVaultKeyMappingHandler)

    fun createAuroraVolume(auroraDeploymentSpec: AuroraDeploymentSpec): AuroraVolume {
        return AuroraVolume(
            secretVaultName = getSecretVault(auroraDeploymentSpec),
            secretVaultKeys = getSecretVaultKeys(auroraDeploymentSpec),
            keyMappings = auroraDeploymentSpec.getKeyMappings(secretVaultKeyMappingHandler),
            config = getApplicationConfigFiles(auroraDeploymentSpec),
            mounts = getMounts(auroraDeploymentSpec)
        )
    }

    private fun createSecretVaultKeyMappingHandler() =
        applicationFiles.find { it.asJsonNode.at("/secretVault/keyMappings") != null }?.let {
            AuroraConfigFieldHandler("secretVault/keyMappings")
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
                AuroraConfigFieldHandler(
                    "mounts/$mountName/path",
                    validator = { it.required("Path is required for mount") }),
                AuroraConfigFieldHandler(
                    "mounts/$mountName/type",
                    validator = { it.oneOf(MountType.values().map { it.name }) }),
                AuroraConfigFieldHandler("mounts/$mountName/mountName", defaultValue = mountName),
                AuroraConfigFieldHandler("mounts/$mountName/volumeName", defaultValue = mountName),
                AuroraConfigFieldHandler("mounts/$mountName/exist", defaultValue = false),
                AuroraConfigFieldHandler("mounts/$mountName/content"),
                AuroraConfigFieldHandler("mounts/$mountName/secretVault")
            )
        }
    }

    private fun getApplicationConfigFiles(auroraDeploymentSpec: AuroraDeploymentSpec): Map<String, String>? {

        data class ConfigFieldValue(val fileName: String, val field: String, val escapedValue: String)

        fun extractConfigFieldValues(): List<ConfigFieldValue> {
            return configHandlers
                .map { it.name }
                .filter { it.count { it == '/' } > 1 }
                .map { name ->
                    val value: Any = auroraDeploymentSpec.get(name)
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

    private fun getSecretVault(auroraDeploymentSpec: AuroraDeploymentSpec): String? =
        auroraDeploymentSpec.extractOrNull("secretVault/name")
            ?: auroraDeploymentSpec.extractOrNull("secretVault")

    private fun getSecretVaultKeys(auroraDeploymentSpec: AuroraDeploymentSpec): List<String> =
        auroraDeploymentSpec.extractOrNull("secretVault/keys") ?: listOf()

    private fun getMounts(auroraDeploymentSpec: AuroraDeploymentSpec): List<Mount>? {
        if (mountHandlers.isEmpty()) {
            return null
        }

        val mountNames = mountHandlers.map {
            val (_, name, _) = it.name.split("/", limit = 3)
            name
        }.toSet()

        return mountNames.map { mount ->
            val type: MountType = auroraDeploymentSpec.get("mounts/$mount/type")

            val content: Map<String, String>? = if (type == MountType.ConfigMap) {
                auroraDeploymentSpec.get("mounts/$mount/content")
            } else {
                null
            }
            val secretVaultName = auroraDeploymentSpec.extractOrNull<String?>("mounts/$mount/secretVault")
            Mount(
                auroraDeploymentSpec.get("mounts/$mount/path"),
                type,
                auroraDeploymentSpec.get("mounts/$mount/mountName"),
                auroraDeploymentSpec.get("mounts/$mount/volumeName"),
                auroraDeploymentSpec.get("mounts/$mount/exist"),
                content,
                secretVaultName
            )
        }
    }
}