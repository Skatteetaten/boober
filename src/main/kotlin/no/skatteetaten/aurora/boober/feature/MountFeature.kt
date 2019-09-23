package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.*
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeMount
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.mapper.v1.findSubKeys
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultRequest
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.required
import org.apache.commons.codec.binary.Base64
import org.springframework.stereotype.Service

@Service
class MountFeature(
        val vaultProvider: VaultProvider
) : Feature {
    override fun handlers(header: AuroraDeploymentContext): Set<AuroraConfigFieldHandler> {
        val mountKeys = header.applicationFiles.findSubKeys("mounts")

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
        }.toSet()
    }

    override fun generate(adc: AuroraDeploymentContext): Set<AuroraResource> {

        val mounts = getMounts(adc)
        val configMounts = mounts.filter { !it.exist && it.type == MountType.ConfigMap && it.content != null }

        val secrets = generateSecrets(mounts, adc)
        val configMaps = generateConfigMaps(configMounts, adc)

        return configMaps.addIfNotNull(secrets).map {
            AuroraResource("${it.metadata.name}-${it.kind}", it)
        }.toSet()
    }

    private fun generateConfigMaps(configMounts: List<Mount>, adc: AuroraDeploymentContext): List<ConfigMap> {
        return configMounts.filter { it.type == MountType.ConfigMap }
                .filter { it.content != null }
                .map {
                    newConfigMap {
                        metadata {
                            name = it.volumeName
                            namespace = adc.namespace
                        }
                        data = it.content
                    }
                }
    }

    private fun generateSecrets(mounts: List<Mount>, adc: AuroraDeploymentContext): List<Secret> {
        val secretVaults = mounts.filter { !it.exist && it.type == MountType.Secret && it.secretVaultName != null }

        val vaultReponse = secretVaults.map {
            VaultRequest(
                    collectionName = adc.affiliation,
                    name = it.secretVaultName!!
            )
        }.let {
            vaultProvider.findVaultData(it)
        }

        return secretVaults.map {
            newSecret {
                metadata {
                    name = it.volumeName.ensureStartWith(adc.name, "-")
                    namespace = adc.namespace
                }
                data = vaultReponse.getVaultData(it.secretVaultName!!).mapValues { Base64.encodeBase64String(it.value) }
            }
        }
    }

    override fun modify(adc: AuroraDeploymentContext, resources: Set<AuroraResource>) {

        val mounts = getMounts(adc)


        if (mounts.isNotEmpty()) {

            val volumes = mounts.podVolumes(adc.name)
            val volumeMounts = mounts.volumeMount()

            val envVars = mounts.map {
                "VOLUME_${it.volumeName}".toUpperCase() to it.path
            }.toMap().toEnvVars()


            resources.forEach {
                if (it.resource.kind == "DeploymentConfig") {
                    val dc: DeploymentConfig = jacksonObjectMapper().convertValue(it.resource)
                    dc.spec.template.spec.volumes.plusAssign(volumes)
                    dc.spec.template.spec.containers.forEach { container ->
                        container.volumeMounts.plusAssign(volumeMounts)
                        container.env.addAll(envVars)
                    }
                }
            }
        }
    }

    private fun getMounts(auroraDeploymentSpec: AuroraDeploymentContext): List<Mount> {

        // TODO: review to not use handlers
        val mountHandlers = handlers(auroraDeploymentSpec);

        if (mountHandlers.isEmpty()) {
            return listOf()
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
                    path = auroraDeploymentSpec["mounts/$mount/path"],
                    type = type,
                    mountName = auroraDeploymentSpec["mounts/$mount/mountName"],
                    volumeName = auroraDeploymentSpec["mounts/$mount/volumeName"],
                    exist = auroraDeploymentSpec["mounts/$mount/exist"],
                    content = content,
                    secretVaultName = secretVaultName
            )
        }
    }

}

fun List<Mount>.volumeMount(): List<VolumeMount> {
    return this.map {
        newVolumeMount {
            name = it.normalizeMountName()
            mountPath = it.path
        }
    }
}

fun List<Mount>.podVolumes(appName: String): List<Volume> {
    return this.map {
        val volumeName = it.getNamespacedVolumeName(appName)
        newVolume {
            name = it.normalizeMountName()
            when (it.type) {
                MountType.ConfigMap -> configMap {
                    name = volumeName
                }
                MountType.Secret -> secret {
                    secretName = volumeName
                }
                MountType.PVC -> persistentVolumeClaim {
                    claimName = volumeName
                }
            }
        }
    }
}