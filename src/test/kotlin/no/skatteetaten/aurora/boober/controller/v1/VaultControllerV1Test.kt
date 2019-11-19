package no.skatteetaten.aurora.boober.controller.v1

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.vault.VaultService
import no.skatteetaten.aurora.boober.utils.createTestVault
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.contentTypeJson
import no.skatteetaten.aurora.mockmvc.extensions.delete
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.put
import no.skatteetaten.aurora.mockmvc.extensions.responseHeader
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpHeaders

@WebMvcTest(controllers = [VaultControllerV1::class])
class VaultControllerV1Test : AbstractControllerTest() {

    @MockkBean
    private lateinit var service: VaultService

    val collectionKey = "myvaultcollection"

    val vault1 = createTestVault(
        vaultCollectionName = collectionKey,
        vaultName = "secret",
        secretName = "latest.properties",
        fileContents = "SECRET_PASS=asdlfkjaølfjaøf"
    )

    val vault2 = createTestVault(
        vaultCollectionName = collectionKey,
        vaultName = "myvault2",
        secretName = "latest.properties",
        fileContents = "SECRET_ENV=foobar"
    )

    @Test
    fun `List vaults in collection`() {
        val allVaults = listOf(vault1, vault2)

        every { service.findAllVaultsWithUserAccessInVaultCollection(collectionKey) } returns allVaults

        mockMvc.get(Path("/v1/vault/{vaultCollection}", collectionKey)) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.count").equalsValue(2)
            responseJsonPath("$.items[0].name").equalsValue("secret")
            responseJsonPath("$.items[1].name").equalsValue("myvault2")
        }
    }

    @Test
    fun `Get vault in collection`() {
        every { service.findVault(collectionKey, "secret") } returns vault1.vault!!

        mockMvc.get(Path("/v1/vault/{vaultCollection}/{vaultKey}", collectionKey, "secret")) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.count").equalsValue(1)
            responseJsonPath("$.items[0].name").equalsValue("secret")
        }
    }

    @Test
    fun `Get vault file`() {
        val fileContents = "SECRET_PASS=asdlfkjaølfjaøf".toByteArray()
        every { service.findFileInVault(collectionKey, "secret", "latest.properties") } returns fileContents

        val vaults = Response(items = listOf(VaultFileResource.fromDecodedBytes(fileContents)))

        mockMvc.get(
            Path(
                "/v1/vault/{vaultCollection}/{vaultKey}/{fileName}",
                collectionKey,
                "secret",
                "latest.properties"
            )
        ) {
            statusIsOk()
            responseJsonPath("$").equalsObject(vaults)
            responseHeader("Etag").equals("792f98952392f2d201c82154f5b18dd8")
        }
    }

    @Test
    fun `Save vault`() {

        val payload = AuroraSecretVaultPayload(
            name = "myvault",
            permissions = emptyList(),
            secrets = mapOf("latest.properites" to "U0VDUkVUX1BBU1M9YXNkbGZramHDuGxmamHDuGY=")
        )

        every { service.import(collectionKey, payload.name, emptyList(), any()) } returns vault1.vault!!

        mockMvc.put(
            path = Path("/v1/vault/{vaultCollection}", collectionKey),
            headers = HttpHeaders().contentTypeJson(),
            body = payload
        ) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.count").equalsValue(1)
            responseJsonPath("$.items[0].name").equalsValue("secret")
        }
    }

    @Test
    fun `Delete vault in collection`() {

        every { service.deleteVault(collectionKey, "secret") } returns Unit
        mockMvc.delete(Path("/v1/vault/{vaultCollection}/{vaultKey}", collectionKey, "secret")) {
            statusIsOk().responseJsonPath("$.success").isTrue()
        }
    }
}
