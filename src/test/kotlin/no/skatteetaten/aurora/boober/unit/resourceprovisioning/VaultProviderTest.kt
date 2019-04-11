package no.skatteetaten.aurora.boober.unit.resourceprovisioning

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultResults
import no.skatteetaten.aurora.boober.service.vault.EncryptedFileVault
import no.skatteetaten.aurora.boober.service.vault.VaultService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.support.PropertiesLoaderUtils
import java.io.File
import java.util.Properties

class VaultProviderTest {

    val COLLECTION_NAME = "paas"
    val VAULT_NAME = "test"

    lateinit var vaultService: VaultService
    lateinit var vaultProvider: VaultProvider

    @BeforeEach
    fun setup() {

        vaultService = mockk()
        every {
            vaultService.findVault(COLLECTION_NAME, VAULT_NAME)
        } returns EncryptedFileVault.createFromFolder(File("./src/test/resources/samples/config/secret/"))

        vaultProvider = VaultProvider(vaultService)
    }

    @Test
    fun `Find filtered vault data`() {
        val requests = listOf(
            VaultRequest(
                collectionName = COLLECTION_NAME,
                name = VAULT_NAME,
                keys = listOf("Boober"),
                keyMappings = emptyMap()
            )
        )

        val results = vaultProvider.findVaultData(requests)
        val properties = loadProperties(results)

        assertThat(results.vaultIndex.size).isEqualTo(1)
        assertThat(properties["Boober"]).isEqualTo("1")
    }

    fun loadProperties(results: VaultResults): Properties {
        val vaultData = results.getVaultData(VAULT_NAME)
        return PropertiesLoaderUtils.loadProperties(ByteArrayResource(vaultData["latest.properties"]!!))
    }
}
