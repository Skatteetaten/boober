package no.skatteetaten.aurora.boober.service.resourceprovisioning

import no.skatteetaten.aurora.boober.service.vault.VaultService
import no.skatteetaten.aurora.boober.utils.PropertiesException
import no.skatteetaten.aurora.boober.utils.filterProperties
import org.springframework.stereotype.Service

data class VaultRequest(val collectionName: String, val name: String, val keys: List<String> )

typealias VaultData = Map<String, ByteArray>
typealias VaultIndex = Map<String, VaultData>

class VaultResults(private val vaultIndex: VaultIndex) {
    fun getVaultData(secretVaultName: String): VaultData {
        return vaultIndex.get(secretVaultName)
                ?: throw IllegalArgumentException("No data for vault $secretVaultName was provisioned")
    }
}

@Service
class VaultProvider(val vaultService: VaultService) {

    fun findVaultData(vaultRequests: List<VaultRequest>): VaultResults? {

        val filteredVaultIndex = vaultRequests.associateBy(
            { it.name },
            {
                val vaultContents = vaultService.findVault(it.collectionName, it.name).secrets
                try {
                    filterVaultData(vaultContents, it.keys)
                } catch (pe : PropertiesException) {
                    val propName = it.name
                    throw RuntimeException("Error when reading properties from $propName.", pe)
                }
            }
        )

        return VaultResults(filteredVaultIndex)
    }

    private fun filterVaultData(vaultData : VaultData, secretVaultKeys : List<String>) : VaultData {

        //if there are no secretVaultKeys specified, use all the keys
        if (secretVaultKeys.isEmpty()) return vaultData
        
        return vaultData
                .mapValues {
                    //if the vault contain a .properties-file, we filter based on secretVaultKeys
                    if (it.key.contains(".properties")){
                        filterProperties(it.value, secretVaultKeys)
                    } else {
                        it.value
                    }
                }.toMap()
    }

}