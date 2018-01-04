package no.skatteetaten.aurora.boober.service.internal

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType
import no.skatteetaten.aurora.boober.service.ProvisioningResult
import no.skatteetaten.aurora.boober.service.SchemaProvisionResult
import no.skatteetaten.aurora.boober.service.SchemaProvisionResults
import no.skatteetaten.aurora.boober.service.VaultResults
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.ensureStartWith


fun findAndCreateMounts(deploymentSpec: AuroraDeploymentSpec, provisioningResult: ProvisioningResult?): List<Mount> {

    val configMounts = createMountsFromDeploymentSpec(deploymentSpec)

    val vaultMounts: List<Mount> = provisioningResult?.vaultResults
            ?.let { populateVaultMounts(deploymentSpec, it) }
            .orEmpty()

    val databaseMounts = provisioningResult?.schemaProvisionResults
            ?.let { createDatabaseMounts(deploymentSpec, it) }
            .orEmpty()

    return configMounts + vaultMounts + databaseMounts
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

    val certMount = deploymentSpec.deploy?.certificateCn?.let {
        Mount(path = "/u01/secrets/app/${deploymentSpec.name}-cert",
                type = MountType.Secret,
                volumeName = "${deploymentSpec.name}-cert",
                mountName = "${deploymentSpec.name}-cert",
                exist = true,
                content = null)
        //TODO: Add sprocket content here
    }
    return listOf<Mount>().addIfNotNull(configMount).addIfNotNull(certMount)
}

private fun populateVaultMounts(deploymentSpec: AuroraDeploymentSpec, vaultResults: VaultResults): List<Mount> {
    val mounts: List<Mount> = deploymentSpec.volume?.mounts?.map {
        if (it.exist) {
            it
        } else {
            val content = if (it.type == MountType.Secret && it.secretVaultName != null)
                vaultResults.getVaultData(it.secretVaultName)
            else it.content
            it.copy(
                    volumeName = it.volumeName.ensureStartWith(deploymentSpec.name, "-"),
                    content = content
            )
        }
    } ?: emptyList()

    val secretVaultMount = deploymentSpec.volume?.secretVaultName?.let {
        Mount(path = "/u01/config/secret",
                type = MountType.Secret,
                volumeName = deploymentSpec.name,
                mountName = "secrets",
                exist = false,
                content = vaultResults.getVaultData(it),
                secretVaultName = it)
    }
    val allVaultMounts = mounts.addIfNotNull(secretVaultMount)
    allVaultMounts.map {

    }
    return allVaultMounts
}

private fun createDatabaseMounts(deploymentSpec: AuroraDeploymentSpec,
                                 schemaProvisionResults: SchemaProvisionResults): List<Mount> {

/*
        val certMount = auroraDeploymentSpec.deploy?.certificateCn?.let {
            Mount(path = "/u01/secrets/app/${auroraDeploymentSpec.name}-cert",
                    type = MountType.Secret,
                    volumeName = "${auroraDeploymentSpec.name}-cert",
                    mountName = "${auroraDeploymentSpec.name}-cert",
                    exist = true,
                    content = null)
            //TODO: Add sprocket content here
        }
*/
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
