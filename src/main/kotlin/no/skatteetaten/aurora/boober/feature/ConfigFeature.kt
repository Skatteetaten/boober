package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.configMap
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newConfigMap
import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.kubernetes.newVolume
import com.fkorotkov.kubernetes.newVolumeMount
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.Paths.configPath
import no.skatteetaten.aurora.boober.model.addVolumesAndMounts
import no.skatteetaten.aurora.boober.model.findConfigFieldHandlers
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.convertValueToString
import no.skatteetaten.aurora.boober.utils.ensureEndsWith
import org.springframework.stereotype.Service

@Service
class ConfigFeature : Feature {

    fun configHandlers(cmd: AuroraContextCommand) = cmd.applicationFiles.findConfigFieldHandlers()

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return configHandlers(cmd).toSet()
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {

        val configFiles = getApplicationConfigFiles(adc, cmd)
        if (configFiles.isNullOrEmpty()) {
            return emptySet()
        }

        val resource = newConfigMap {
            metadata {
                name = adc.name
                namespace = adc.namespace
            }
            data = configFiles
        }
        return setOf(generateResource(resource))
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {

        val configSecretExist = getApplicationConfigFiles(adc, cmd)

        val mounts = configSecretExist?.let {
            listOf(newVolumeMount {
                name = "config"
                mountPath = "$configPath/configmap"
            })
        } ?: emptyList()

        val volumes = configSecretExist?.let {
            listOf(newVolume {
                name = "config"
                configMap {
                    name = adc.name
                }
            }
            )
        } ?: emptyList()

        val configEnv = configSecretExist?.let {
            newEnvVar {
                name = "VOLUME_CONFIG"
                value = "$configPath/configmap"
            }
        }

        val env = adc.getConfigEnv(configHandlers(cmd))
            .toEnvVars()
            .addIfNotNull(configEnv)
        resources.addVolumesAndMounts(env, volumes, mounts, this::class.java)
    }

    private fun getApplicationConfigFiles(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Map<String, String>? {

        data class ConfigFieldValue(val fileName: String, val field: String, val escapedValue: String)

        fun extractConfigFieldValues(): List<ConfigFieldValue> {
            return configHandlers(cmd)
                .map { it.name }
                .filter { handler -> handler.count { it == '/' } > 1 }
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
}
