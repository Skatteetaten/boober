package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.*
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.mapper.v1.convertValueToString
import no.skatteetaten.aurora.boober.mapper.v1.findConfigFieldHandlers
import no.skatteetaten.aurora.boober.mapper.v1.findSubKeys
import no.skatteetaten.aurora.boober.model.AuroraSecret
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultSecretEnvResult
import no.skatteetaten.aurora.boober.utils.*
import org.apache.commons.codec.binary.Base64

class ConfigFeature(
        val vaultProvider: VaultProvider
) : Feature {

    fun secretVaultKeyMappingHandlers(header: AuroraDeploymentContext) = header.applicationFiles.find {
        it.asJsonNode.at("/secretVault/keyMappings") != null
    }?.let {
        AuroraConfigFieldHandler("secretVault/keyMappings")
    }

    fun configHandlers(header: AuroraDeploymentContext) = header.applicationFiles.findConfigFieldHandlers()

    override fun handlers(header: AuroraDeploymentContext): Set<AuroraConfigFieldHandler> {

        val secretVaultsHandlers = header.applicationFiles.findSubKeys("secretVaults").flatMap { key ->
            listOf(
                    AuroraConfigFieldHandler("secretVaults/$key/name", defaultValue = key),
                    AuroraConfigFieldHandler("secretVaults/$key/enabled", defaultValue = true),
                    AuroraConfigFieldHandler("secretVaults/$key/file", defaultValue = "latest.properties"),
                    AuroraConfigFieldHandler("secretVaults/$key/keys"),
                    AuroraConfigFieldHandler("secretVaults/$key/keyMappings")
            )
        }
        return listOf(
                AuroraConfigFieldHandler("secretVault"),
                AuroraConfigFieldHandler("secretVault/name"),
                AuroraConfigFieldHandler("secretVault/keys"))
                .addIfNotNull(secretVaultKeyMappingHandlers(header))
                .addIfNotNull(secretVaultsHandlers)
                .addIfNotNull(configHandlers(header))
                .toSet()
    }

    override fun generate(adc: AuroraDeploymentContext): Set<AuroraResource> {

        val secretEnvResult = handleSecretEnv(adc)

        val secrets: List<AuroraResource> = secretEnvResult.map {
            val secret = newSecret {
                metadata {
                    name = it.name
                    namespace = adc.namespace
                }
                data = it.secrets.mapValues { Base64.encodeBase64String(it.value) }
            }
            AuroraResource("${secret.metadata.name}-${secret.kind}", secret)
        }

        val configMap: AuroraResource? = getApplicationConfigFiles(adc)?.let {
            AuroraResource("${adc.name}-configmap", newConfigMap {
                metadata {
                    name = adc.name
                    namespace = adc.namespace
                }
                data = it
            })
        }

        return secrets.addIfNotNull(configMap).toSet()
    }


    private fun handleSecretEnv(adc: AuroraDeploymentContext): List<VaultSecretEnvResult> {
        val secrets = getSecretVaults(adc)
        return secrets.mapNotNull { secret: AuroraSecret ->
            val request = VaultRequest(
                    collectionName = adc.affiliation,
                    name = secret.secretVaultName,
                    keys = secret.secretVaultKeys,
                    keyMappings = secret.keyMappings
            )
            vaultProvider.findVaultDataSingle(request)[secret.file]?.let { file ->
                val properties = filterProperties(file, secret.secretVaultKeys, secret.keyMappings)
                properties.map {
                    it.key.toString() to it.value.toString().toByteArray()
                }
            }?.let {
                VaultSecretEnvResult(secret.secretVaultName.ensureStartWith(adc.name, "-"), it.toMap())
            }
        }
    }

    override fun modify(adc: AuroraDeploymentContext, resources: Set<AuroraResource>) {

        val secretEnv: List<EnvVar> = handleSecretEnv(adc).flatMap { result ->
            result.secrets.map { secretValue ->
                newEnvVar {
                    name = secretValue.key
                    valueFrom {
                        secretKeyRef {
                            key = secretValue.key
                            name = result.name
                            optional = false
                        }
                    }
                }
            }
        }
        val env = adc.getConfigEnv(configHandlers(adc)).toEnvVars()
        val configVolumeAndMount = getApplicationConfigFiles(adc)?.let {
            val mount = newVolumeMount {
                name = "config"
                mountPath = "/u01/config/configmap"
            }

            val volume = newVolume {
                name = "config"
                configMap {
                    name = adc.name
                }
            }
            mount to volume
        }
        resources.forEach {
            if (it.resource.kind == "DeploymentConfig") {
                val dc: DeploymentConfig = jacksonObjectMapper().convertValue(it.resource)

                configVolumeAndMount?.let { (_, volume) ->
                    dc.spec.template.spec.volumes.plusAssign(volume)
                }
                dc.spec.template.spec.containers.forEach { container ->
                    if (env.isNotEmpty()) {
                        container.env.addAll(env)
                    }
                    if (secretEnv.isNotEmpty()) {
                        container.env.addAll(secretEnv)
                    }
                    configVolumeAndMount?.let { (mount, _) ->
                        container.env.add(newEnvVar {
                            name = "VOLUME_CONFIG"
                            value = "/u01/config/configmap"
                        })
                        container.volumeMounts.plusAssign(mount)
                    }
                }
            }
        }
    }

    private fun getSecretVaults(adc: AuroraDeploymentContext): List<AuroraSecret> {
        val secret = getSecretVault(adc)?.let {
            AuroraSecret(
                    secretVaultName = it,
                    keyMappings = adc.getKeyMappings(secretVaultKeyMappingHandlers(adc)),
                    secretVaultKeys = getSecretVaultKeys(adc),
                    file = "latest.properties",
                    name = adc.name
            )
        }

        val secretVaults = adc.applicationFiles.findSubKeys("secretVaults").mapNotNull {
            val enabled: Boolean = adc["secretVaults/$it/enabled"]

            if (!enabled) {
                null
            } else {
                AuroraSecret(
                        secretVaultKeys = adc.getOrNull("secretVaults/$it/keys") ?: listOf(),
                        keyMappings = adc.getOrNull("secretVaults/$it/keyMappings"),
                        file = adc["secretVaults/$it/file"],
                        name = adc.replacer.replace(it).ensureStartWith(adc.name, "-").normalizeKubernetesName(),
                        secretVaultName = adc["secretVaults/$it/name"]
                )
            }
        }

        return secretVaults.addIfNotNull(secret)
    }

    private fun getApplicationConfigFiles(adc: AuroraDeploymentContext): Map<String, String>? {

        data class ConfigFieldValue(val fileName: String, val field: String, val escapedValue: String)

        fun extractConfigFieldValues(): List<ConfigFieldValue> {
            return configHandlers(adc)
                    .map { it.name }
                    .filter { it.count { it == '/' } > 1 }
                    .map { name ->
                        val value: Any = adc[name]
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

    private fun getSecretVault(auroraDeploymentSpec: AuroraDeploymentContext): String? =
            auroraDeploymentSpec.getOrNull("secretVault/name")
                    ?: auroraDeploymentSpec.getOrNull("secretVault")

    private fun getSecretVaultKeys(auroraDeploymentSpec: AuroraDeploymentContext): List<String> =
            auroraDeploymentSpec.getOrNull("secretVault/keys") ?: listOf()
}