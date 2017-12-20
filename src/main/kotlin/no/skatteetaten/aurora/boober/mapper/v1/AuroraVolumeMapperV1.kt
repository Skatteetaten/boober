package no.skatteetaten.aurora.boober.mapper.v1

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.Vault
import no.skatteetaten.aurora.boober.model.AuroraVolume
import no.skatteetaten.aurora.boober.model.MountType
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.required

class AuroraVolumeMapperV1(val applicationFiles: List<AuroraConfigFile>,
                           val vaults: Map<String, Vault>) {


    val mountHandlers = findMounts()
    val configHandlers = applicationFiles.findConfigFieldHandlers()

    val handlers = configHandlers + mountHandlers + listOf(
            AuroraConfigFieldHandler("secretVault", validator = validateSecrets())
    )


    fun auroraDeploymentCore(auroraConfigFields: AuroraConfigFields): AuroraVolume {

        return AuroraVolume(
                secrets = auroraConfigFields.extractOrNull<String?>("secretVault")?.let {
                    vaults[it]?.secrets
                },
                config = auroraConfigFields.getConfigMap(configHandlers),
                mounts = auroraConfigFields.getMounts(mountHandlers, vaults),
                permissions = auroraConfigFields.extractOrNull<String?>("secretVault")?.let {
                    vaults[it]?.permissions
                })
    }


    private fun validateSecrets(): (JsonNode?) -> Exception? {
        return { json ->

            val secretVault = json?.textValue()
            val secrets = secretVault?.let {
                vaults[it]?.secrets
            }

            if (secretVault != null && (secrets == null || secrets.isEmpty())) {
                IllegalArgumentException("No secret vault named=$secretVault.")
            } else {
                null
            }
        }
    }


    fun findMounts(): List<AuroraConfigFieldHandler> {

        val mountKeys = applicationFiles.findSubKeys("mounts")

        return mountKeys.flatMap { mountName ->
            listOf(
                    AuroraConfigFieldHandler("mounts/$mountName/path", validator = { it.required("Path is required for mount") }),
                    AuroraConfigFieldHandler("mounts/$mountName/type", validator = { it.oneOf(MountType.values().map { it.name }) }),
                    AuroraConfigFieldHandler("mounts/$mountName/mountName", defaultValue = mountName),
                    AuroraConfigFieldHandler("mounts/$mountName/volumeName", defaultValue = mountName),
                    AuroraConfigFieldHandler("mounts/$mountName/exist", defaultValue = false),
                    AuroraConfigFieldHandler("mounts/$mountName/content"),
                    AuroraConfigFieldHandler("mounts/$mountName/secretVault")
            )

        }
    }


}