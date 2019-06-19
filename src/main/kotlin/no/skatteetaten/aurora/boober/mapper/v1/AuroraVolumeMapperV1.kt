package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraSecret
import no.skatteetaten.aurora.boober.model.AuroraVolume
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.ensureEndsWith
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.normalizeKubernetesName
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.required
import org.apache.commons.text.StringSubstitutor

class AuroraVolumeMapperV1(
    private val applicationFiles: List<AuroraConfigFile>,
    val name: String,
    val replacer: StringSubstitutor
) {

    private val mountHandlers = createMountHandlers()
    val configHandlers = applicationFiles.findConfigFieldHandlers()
    private val secretVaultHandlers = createSecretVaultHandlers()
    private val secretVaultKeyMappingHandler = createSecretVaultKeyMappingHandler()

    val handlers = configHandlers + mountHandlers +
        secretVaultHandlers + listOfNotNull(secretVaultKeyMappingHandler) +
        createSecretVaultsHandlers()

    fun createAuroraVolume(auroraDeploymentSpec: AuroraDeploymentSpec): AuroraVolume {
        val secret = getSecretVault(auroraDeploymentSpec)?.let {
            AuroraSecret(
                secretVaultName = it,
                keyMappings = auroraDeploymentSpec.getKeyMappings(secretVaultKeyMappingHandler),
                secretVaultKeys = getSecretVaultKeys(auroraDeploymentSpec),
                file = "latest.properties",
                name = it.ensureStartWith(name, "-").normalizeKubernetesName()
            )
        }

        val secretVaults = applicationFiles.findSubKeys("secretVaults").mapNotNull {
            val enabled: Boolean = auroraDeploymentSpec["secretVaults/$it/enabled"]

            if (!enabled) {
                null
            } else {
                AuroraSecret(
                    secretVaultKeys = auroraDeploymentSpec.getOrNull("secretVaults/$it/keys") ?: listOf(),
                    keyMappings = auroraDeploymentSpec.getOrNull("secretVaults/$it/keyMappings"),
                    file = auroraDeploymentSpec["secretVaults/$it/file"],
                    name = replacer.replace(it).ensureStartWith(name, "-").normalizeKubernetesName(),
                    secretVaultName = auroraDeploymentSpec["secretVaults/$it/name"]
                )
            }
        }
        val secrets = secretVaults.addIfNotNull(secret)
        return AuroraVolume(
            secrets = secrets,
            config = getApplicationConfigFiles(auroraDeploymentSpec),
            mounts = getMounts(auroraDeploymentSpec)
        )
    }

    private fun createSecretVaultsHandlers(): List<AuroraConfigFieldHandler> {
        val vaults = applicationFiles.findSubKeys("secretVaults")

        return vaults.flatMap { key ->
            listOf(
                AuroraConfigFieldHandler("secretVaults/$key/name", defaultValue = key),
                AuroraConfigFieldHandler("secretVaults/$key/enabled", defaultValue = true),
                AuroraConfigFieldHandler("secretVaults/$key/file", defaultValue = "latest.properties"),
                AuroraConfigFieldHandler("secretVaults/$key/keys"),
                AuroraConfigFieldHandler("secretVaults/$key/keyMappings")
            )
        }
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
                    val value: Any = auroraDeploymentSpec[name]
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
        auroraDeploymentSpec.getOrNull("secretVault/name")
            ?: auroraDeploymentSpec.getOrNull("secretVault")

    private fun getSecretVaultKeys(auroraDeploymentSpec: AuroraDeploymentSpec): List<String> =
        auroraDeploymentSpec.getOrNull("secretVault/keys") ?: listOf()

    private fun getMounts(auroraDeploymentSpec: AuroraDeploymentSpec): List<Mount>? {
        if (mountHandlers.isEmpty()) {
            return null
        }

        val mountNames = mountHandlers.map {
            val (_, name, _) = it.name.split("/", limit = 3)
            name
        }.toSet()

        return mountNames.map { mount ->
            val type: MountType = auroraDeploymentSpec["mounts/$mount/type"]

            val content: Map<String, String>? = if (type == MountType.ConfigMap) {
                auroraDeploymentSpec["mounts/$mount/content"]
            } else {
                null
            }
            val secretVaultName = auroraDeploymentSpec.getOrNull<String?>("mounts/$mount/secretVault")
            Mount(
                auroraDeploymentSpec["mounts/$mount/path"],
                type,
                auroraDeploymentSpec["mounts/$mount/mountName"],
                auroraDeploymentSpec["mounts/$mount/volumeName"],
                auroraDeploymentSpec["mounts/$mount/exist"],
                content,
                secretVaultName
            )
        }
    }
}