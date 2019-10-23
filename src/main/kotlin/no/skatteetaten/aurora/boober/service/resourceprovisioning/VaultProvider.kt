package no.skatteetaten.aurora.boober.service.resourceprovisioning

import no.skatteetaten.aurora.boober.service.vault.VaultService
import org.springframework.stereotype.Service

data class VaultRequest(
    val collectionName: String,
    val name: String
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

// TODO: Why provider and service here?
@Service
class VaultProvider(private val vaultService: VaultService) {

    fun findFileInVault(
        vaultCollectionName: String,
        vaultName: String,
        fileName: String
    ): ByteArray {
        return vaultService.findFileInVault(vaultCollectionName, vaultName, fileName)
    }

    fun vaultExists(vaultCollectionName: String, vaultName: String): Boolean {
        return vaultService.vaultExists(vaultCollectionName, vaultName)
    }

    fun findVaultKeys(vaultCollectionName: String, vaultName: String, fileName: String): Set<String> {
        return vaultService.findVaultKeys(vaultCollectionName, vaultName, fileName)
    }

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
