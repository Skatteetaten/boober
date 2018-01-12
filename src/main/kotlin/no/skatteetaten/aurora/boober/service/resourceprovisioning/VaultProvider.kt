package no.skatteetaten.aurora.boober.service.resourceprovisioning

import no.skatteetaten.aurora.boober.service.vault.VaultService
import org.springframework.stereotype.Service

data class VaultRequest(val collectionName: String, val name: String)

typealias VaultData = Map<String, String>
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

        val vaultIndex: VaultIndex = vaultRequests.associateBy(
                { it.name },
                { vaultService.findVault(it.collectionName, it.name).secrets }
        )
        return VaultResults(vaultIndex)
    }
}