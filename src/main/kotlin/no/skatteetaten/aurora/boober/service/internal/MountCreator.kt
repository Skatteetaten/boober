package no.skatteetaten.aurora.boober.service.internal

import no.skatteetaten.aurora.boober.mapper.v1.ToxiProxyDefaults
import no.skatteetaten.aurora.boober.mapper.v1.getToxiProxyConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ProvisioningResult
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionResult
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionResults
import no.skatteetaten.aurora.boober.utils.addIfNotNull


fun findAndCreateMounts(deploymentSpec: AuroraDeploymentSpec, provisioningResult: ProvisioningResult?): List<Mount> {

    val configMounts = createMountsFromDeploymentSpec(deploymentSpec)

    val databaseMounts = provisioningResult?.schemaProvisionResults
            ?.let { createDatabaseMounts(deploymentSpec, it) }
            .orEmpty()

    val toxiProxyMounts = createToxiProxyMounts(deploymentSpec)

    return configMounts + databaseMounts + toxiProxyMounts
}

private fun createMountsFromDeploymentSpec(deploymentSpec: AuroraDeploymentSpec): List<Mount> {

    val configMount = deploymentSpec.volume?.config?.let {

        Mount(path = "/u01/config/configmap",
                type = MountType.ConfigMap,
                volumeName = deploymentSpec.name,
                mountName = "config",
                exist = false,
                content = it)
    }

    val secretVaultMount = deploymentSpec.volume?.secretVaultName?.let {
        Mount(path = "/u01/config/secret",
                type = MountType.Secret,
                volumeName = deploymentSpec.name,
                mountName = "secrets",
                exist = false,
                content = null,
                secretVaultName = it)
    }

    val certMount = deploymentSpec.deploy?.certificateCn?.let {
        Mount(path = "/u01/secrets/app/${deploymentSpec.name}-cert",
                type = MountType.Secret,
                volumeName = "${deploymentSpec.name}-cert",
                mountName = "${deploymentSpec.name}-cert",
                exist = true,
                content = null)
        //TODO: Add sprocket content here
    }
    return listOf<Mount>().addIfNotNull(secretVaultMount).addIfNotNull(configMount).addIfNotNull(certMount).addIfNotNull(deploymentSpec.volume?.mounts)
}

private fun createDatabaseMounts(deploymentSpec: AuroraDeploymentSpec,
                                 schemaProvisionResults: SchemaProvisionResults): List<Mount> {

    val schemaResults: List<SchemaProvisionResult> = schemaProvisionResults.results
    val databaseMounts = schemaResults.map {
        val mountPath = "${it.request.schemaName}-db".toLowerCase()
        val volumeName = "${deploymentSpec.name}-${it.request.schemaName}-db".toLowerCase()
        Mount(path = "/u01/secrets/app/$mountPath",
                type = MountType.Secret,
                mountName = mountPath,
                volumeName = volumeName,
                exist = true,
                content = null)
    }

    return databaseMounts
}

private fun createToxiProxyMounts(deploymentSpec: AuroraDeploymentSpec): List<Mount> {

    return deploymentSpec.deploy?.toxiProxy?.let {
        listOf(Mount(
            path = "/u01/config",
            type = MountType.ConfigMap,
            mountName = "${ToxiProxyDefaults.NAME}-volume",
            volumeName = "${ToxiProxyDefaults.NAME}-config",
            exist = false,
            content = mapOf("config.json" to getToxiProxyConfig()),
            targetContainer = ToxiProxyDefaults.NAME))
    }
        .orEmpty()
}


