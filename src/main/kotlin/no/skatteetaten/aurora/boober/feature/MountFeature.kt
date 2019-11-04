package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.configMap
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newConfigMap
import com.fkorotkov.kubernetes.newSecret
import com.fkorotkov.kubernetes.newVolume
import com.fkorotkov.kubernetes.newVolumeMount
import com.fkorotkov.kubernetes.persistentVolumeClaim
import com.fkorotkov.kubernetes.secret
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeMount
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.addVolumesAndMounts
import no.skatteetaten.aurora.boober.model.findSubKeys
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultRequest
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.required
import org.apache.commons.codec.binary.Base64
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class MountFeature(
    val vaultProvider: VaultProvider,
    @Value("\${openshift.cluster}") val cluster: String,
    val openShiftClient: OpenShiftClient
) : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        val mountKeys = cmd.applicationFiles.findSubKeys("mounts")

        return mountKeys.flatMap { mountName ->
            listOf(
                AuroraConfigFieldHandler(
                    "mounts/$mountName/path",
                    validator = { it.required("Path is required for mount") }),
                AuroraConfigFieldHandler(
                    "mounts/$mountName/type",
                    validator = { json -> json.oneOf(MountType.values().map { it.name }) }),
                AuroraConfigFieldHandler(
                    "mounts/$mountName/mountName",
                    defaultValue = mountName
                ),
                AuroraConfigFieldHandler(
                    "mounts/$mountName/volumeName",
                    defaultValue = mountName
                ),
                AuroraConfigFieldHandler(
                    "mounts/$mountName/exist",
                    defaultValue = false
                ),
                AuroraConfigFieldHandler("mounts/$mountName/content"),
                AuroraConfigFieldHandler("mounts/$mountName/secretVault")
            )
        }.toSet()
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {

        val mounts = getMounts(adc, cmd)
        val configMounts = mounts.filter { !it.exist && it.type == MountType.ConfigMap && it.content != null }

        val secrets = generateSecrets(mounts, adc)
        val configMaps = generateConfigMaps(configMounts, adc)

        return configMaps.addIfNotNull(secrets).map {
            generateResource(it)
        }.toSet()
    }

    private fun generateConfigMaps(configMounts: List<Mount>, adc: AuroraDeploymentSpec): List<ConfigMap> {
        return configMounts.filter { it.type == MountType.ConfigMap }
            .filter { it.content != null }
            .map {
                newConfigMap {
                    metadata {
                        name = it.volumeName.ensureStartWith(adc.name, "-")
                        namespace = adc.namespace
                    }
                    data = it.content
                }
            }
    }

    // TODO: The names here should end with a fixed suffix to avoid conflicts with secretVault
    private fun generateSecrets(mounts: List<Mount>, adc: AuroraDeploymentSpec): List<Secret> {
        val secretVaults = mounts.filter { !it.exist && it.type == MountType.Secret && it.secretVaultName != null }

        if (secretVaults.isEmpty()) {
            return emptyList()
        }
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

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {

        val mounts = getMounts(adc, cmd)

        if (mounts.isNotEmpty()) {

            val volumes = mounts.podVolumes(adc.name)
            val volumeMounts = mounts.volumeMount()

            val envVars = mounts.map {
                "VOLUME_${it.volumeName}".toUpperCase() to it.path
            }.toMap().toEnvVars()

            resources.addVolumesAndMounts(envVars, volumes, volumeMounts, this::class.java)
        }
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        cmd: AuroraContextCommand
    ): List<Exception> {
        val mounts = getMounts(adc, cmd)
        val errors = validateConfigMapMountHasContent(mounts, adc)
            .addIfNotNull(validatePVCMounts(mounts))
            .addIfNotNull(validateExistinAndSecretVault(mounts))
        if (!fullValidation || adc.cluster != cluster) {
            return errors
        }
        return errors.addIfNotNull(validateExistingMounts(mounts, adc))
            .addIfNotNull(validateVaultExistence(mounts, adc))
    }

    private fun validateExistinAndSecretVault(mounts: List<Mount>): List<AuroraDeploymentSpecValidationException>? {
        return mounts.filter { it.exist && it.secretVaultName != null }.map {
            AuroraDeploymentSpecValidationException("Secret mount=${it.volumeName} with vaultName set cannot be marked as existing")
        }
    }

    private fun validatePVCMounts(
        mounts: List<Mount>
    ): List<AuroraDeploymentSpecValidationException>? {
        return mounts.filter { !it.exist && it.type == MountType.PVC }.map {
            AuroraDeploymentSpecValidationException("PVC mount=${it.volumeName} must have exist set. We do not support generating mounts for now")
        }
    }

    fun validateConfigMapMountHasContent(
        mounts: List<Mount>,
        adc: AuroraDeploymentSpec
    ): List<AuroraDeploymentSpecValidationException> {
        return mounts.filter { it.type == MountType.ConfigMap && it.content == null }.map {
            AuroraDeploymentSpecValidationException("Mount with type=${it.type} namespace=${adc.namespace} name=${it.volumeName} does not have required content block")
        }
    }

    fun validateVaultExistence(
        mounts: List<Mount>,
        adc: AuroraDeploymentSpec
    ): List<AuroraDeploymentSpecValidationException> {
        val secretMounts = mounts.filter { it.type == MountType.Secret }.mapNotNull { it.secretVaultName }
        return secretMounts.mapNotNull {
            val vaultCollectionName = adc.affiliation
            if (!vaultProvider.vaultExists(vaultCollectionName, it)) {
                AuroraDeploymentSpecValidationException("Referenced Vault $it in Vault Collection $vaultCollectionName does not exist")
            } else null
        }
    }

    private fun validateExistingMounts(mounts: List<Mount>, adc: AuroraDeploymentSpec): List<Exception> {
        return mounts.filter { it.exist }.mapNotNull {
            if (!openShiftClient.resourceExists(
                    kind = it.type.kind,
                    namespace = adc.namespace,
                    name = it.volumeName
                )
            ) {
                AuroraDeploymentSpecValidationException("Required existing resource with type=${it.type} namespace=${adc.namespace} name=${it.volumeName} does not exist.")
            } else null
        }
    }

    private fun getMounts(auroraDeploymentSpec: AuroraDeploymentSpec, cmd: AuroraContextCommand): List<Mount> {

        val mountHandlers = handlers(auroraDeploymentSpec, cmd)

        if (mountHandlers.isEmpty()) {
            return listOf()
        }

        val mountNames = mountHandlers.map {
            val (_, name, _) = it.name.split("/", limit = 3)
            name
        }.toSet()

        return mountNames.map { mount ->
            val type: MountType = auroraDeploymentSpec["mounts/$mount/type"]

            // TODO: bug duplicate configmap name is possible in combination with config nested config
            // TODO: bug here if content is not map
            val content: Map<String, String>? = if (type == MountType.ConfigMap) {
                auroraDeploymentSpec.getOrNull("mounts/$mount/content")
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

enum class MountType(val kind: String) {
    ConfigMap("configmap"),
    Secret("secret"),
    PVC("persistentvolumeclaim")
}

data class Mount(
    val path: String,
    val type: MountType,
    val mountName: String,
    val volumeName: String,
    val exist: Boolean,
    val content: Map<String, String>? = null,
    val secretVaultName: String? = null,
    val targetContainer: String? = null
) {
    fun getNamespacedVolumeName(appName: String): String {
        val name = if (exist) {
            this.volumeName
        } else {
            this.volumeName.ensureStartWith(appName, "-")
        }
        return name.replace("_", "-").toLowerCase()
    }

    fun normalizeMountName() = mountName.replace("_", "-").toLowerCase()
}
