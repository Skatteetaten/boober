package no.skatteetaten.aurora.boober.service.resourceprovisioning

import no.skatteetaten.aurora.boober.service.vault.VaultService
import no.skatteetaten.aurora.boober.utils.PropertiesException
import no.skatteetaten.aurora.boober.utils.filterProperties
import org.springframework.stereotype.Service

// TODO: We can have multiple files from the same vault, so this needs
// name, vaultName and file

data class VaultRequest(
    val collectionName: String,
    val name: String,
    val keys: List<String> = emptyList(),
    val keyMappings: Map<String, String>? = null
)

typealias VaultData = Map<String, ByteArray>
typealias VaultIndex = Map<String, VaultData>

class VaultSecretEnvResult(
    val name: String,
    val secrets: VaultData
)

class VaultResults(val vaultIndex: VaultIndex) {
    fun getVaultData(secretVaultName: String): VaultData {
        return vaultIndex[secretVaultName]
            ?: throw IllegalArgumentException("No data for vault $secretVaultName was provisioned")
    }
}

@Service
class VaultProvider(val vaultService: VaultService) {

    fun findVaultData(vaultRequests: List<VaultRequest>): VaultResults {

        val filteredVaultIndex = vaultRequests.associateBy(
            { it.name },
            { findVaultData(it) }
        )

        return VaultResults(filteredVaultIndex)
    }

    fun findVaultData(it: VaultRequest): VaultData {
        val vaultContents = vaultService.findVault(it.collectionName, it.name).secrets
        return try {
            filterVaultData(vaultContents, it.keys, it.keyMappings)
        } catch (pe: PropertiesException) {
            val propName = it.name
            throw RuntimeException("Error when reading properties from $propName.", pe)
        }
    }

    private fun filterVaultData(vaultData: VaultData, secretVaultKeys: List<String>, keyMappings: Map<String, String>?): VaultData {
        return vaultData
            .mapValues {
                // if the vault contain a .properties-file, we filter based on secretVaultKeys
                if (it.key.contains(".properties")) {
                    filterProperties(it.value, secretVaultKeys, keyMappings)
                } else {
                    it.value
                }
            }.toMap()
    }
}