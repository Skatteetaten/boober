package no.skatteetaten.aurora.boober.service.internal

import no.skatteetaten.aurora.boober.mapper.v1.ToxiProxyDefaults
import no.skatteetaten.aurora.boober.mapper.v1.getToxiProxyConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ProvisioningResult
import no.skatteetaten.aurora.boober.utils.addIfNotNull

fun findAndCreateMounts(
    deploymentSpecInternal: AuroraDeploymentSpecInternal,
    provisioningResult: ProvisioningResult?
): List<Mount> {

    val configMounts = createMountsFromDeploymentSpec(deploymentSpecInternal)

    val databaseMounts = provisioningResult?.schemaProvisionResults
        ?.let { it.createDatabaseMounts(deploymentSpecInternal) }
        .orEmpty()

    val toxiProxyMounts = createToxiProxyMounts(deploymentSpecInternal)

    return configMounts + databaseMounts + toxiProxyMounts
}

private fun createMountsFromDeploymentSpec(deploymentSpecInternal: AuroraDeploymentSpecInternal): List<Mount> {

    val configMount = deploymentSpecInternal.volume?.config?.let {

        Mount(
            path = "/u01/config/configmap",
            type = MountType.ConfigMap,
            volumeName = deploymentSpecInternal.name,
            mountName = "config",
            exist = false,
            content = it
        )
    }

    //TODO: Remove
    val secretVaultMount = deploymentSpecInternal.volume?.secrets?.get(0)?.secretVaultName?.let {
        Mount(
            path = "/u01/config/secret",
            type = MountType.Secret,
            volumeName = deploymentSpecInternal.name,
            mountName = "secrets",
            exist = false,
            content = null,
            secretVaultName = it
        )
    }

    val certMount = deploymentSpecInternal.integration?.certificate?.let {
        Mount(
            path = "/u01/secrets/app/${deploymentSpecInternal.name}-cert",
            type = MountType.Secret,
            volumeName = "${deploymentSpecInternal.name}-cert",
            mountName = "${deploymentSpecInternal.name}-cert",
            exist = true,
            content = null
        )
    }
    return listOf<Mount>().addIfNotNull(secretVaultMount).addIfNotNull(configMount).addIfNotNull(certMount)
        .addIfNotNull(deploymentSpecInternal.volume?.mounts)
}

private fun createToxiProxyMounts(deploymentSpecInternal: AuroraDeploymentSpecInternal): List<Mount> {

    return deploymentSpecInternal.deploy?.toxiProxy?.let {
        listOf(
            Mount(
                path = "/u01/config",
                type = MountType.ConfigMap,
                mountName = "${ToxiProxyDefaults.NAME}-volume",
                volumeName = "${ToxiProxyDefaults.NAME}-config",
                exist = false,
                content = mapOf("config.json" to getToxiProxyConfig()),
                targetContainer = ToxiProxyDefaults.NAME
            )
        )
    }
        .orEmpty()
}
