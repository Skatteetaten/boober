package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.*
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.mapper.v1.findSubKeys
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.required
import org.springframework.stereotype.Service

@Service
class MountFeature() : Feature {
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

        val mounts = getMounts(adc).filter { !it.exist && it.type == MountType.ConfigMap && it.content != null }

        val configMaps = mounts.filter { it.type == MountType.ConfigMap }
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


        return configMaps.map {
            AuroraResource("${it.metadata.name}-${it.kind}", it)
        }.toSet()
    }

    override fun modify(adc: AuroraDeploymentContext, resources: Set<AuroraResource>) {

        val mounts: List<Mount> = getMounts(adc).filter { !it.exist && it.type == MountType.ConfigMap && it.content != null }

        if (mounts.isNotEmpty()) {

            val envVars = mounts.map {
                "VOLUME_${it.volumeName}".toUpperCase() to it.path
            }.toMap().toEnvVars()

            val volumeMounts = mounts.map {
                newVolumeMount {
                    name = it.mountName
                    mountPath = it.path
                }
            }

            val volume = mounts.map {
                newVolume {
                    name = it.mountName
                    configMap {
                        name = it.volumeName
                    }
                }
            }

            resources.forEach {
                if (it.resource.kind == "DeploymentConfig") {
                    val dc: DeploymentConfig = jacksonObjectMapper().convertValue(it.resource)
                    dc.spec.template.spec.volumes.plusAssign(volume)
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