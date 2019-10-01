package no.skatteetaten.aurora.boober.service.resourceprovisioning

import no.skatteetaten.aurora.boober.service.vault.VaultService
import org.springframework.stereotype.Service

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

// TODO: Why a vault provider and a vault service?
@Service
class VaultProvider(val vaultService: VaultService) {

    fun findVaultData(vaultRequests: List<VaultRequest>): VaultResults {

        val filteredVaultIndex = vaultRequests.associateBy(
            { it.name },
            { findVaultDataSingle(it) }
        )

        return VaultResults(filteredVaultIndex)
    }

    fun findVaultDataSingle(it: VaultRequest): VaultData {
        return vaultService.findVault(it.collectionName, it.name).secrets.mapValues { it.value }
    }
}