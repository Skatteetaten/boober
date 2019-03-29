package no.skatteetaten.aurora.boober.controller.v1

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.given
import no.skatteetaten.aurora.boober.controller.Responder
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.vault.VaultService
import no.skatteetaten.aurora.boober.utils.createTestVault
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.mock.withContractResponse
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc

@AutoConfigureRestDocs
@WebMvcTest(controllers = [VaultControllerV1::class], secure = false)
class VaultControllerV1Test(@Autowired private val mockMvc: MockMvc) {

    @MockBean
    private lateinit var service: VaultService

    @MockBean
    private lateinit var responder: Responder

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
        given(service.findAllVaultsWithUserAccessInVaultCollection(collectionKey)).willReturn(allVaults)

        val vaults = given(responder.create(any())).withContractResponse("vault/allvaults") {
            willReturn(content)
        }.mockResponse

        mockMvc.get(Path("/v1/vault/{vaultCollection}", collectionKey)) {
            statusIsOk().responseJsonPath("$").equalsObject(vaults)
        }
    }

    @Test
    fun `get vault in collection`() {

        given(service.findVault(collectionKey, "secret")).willReturn(vault1.vault)

        val vaults = given(responder.create(any())).withContractResponse("vault/vaults") {
            willReturn(content)
        }.mockResponse

        mockMvc.get(Path("/v1/vault/{vaultCollection}/{vaultKey}", collectionKey, "secret")) {
            statusIsOk().responseJsonPath("$").equalsObject(vaults)
        }
    }

    @Test
    fun `get vault file`() {

        val fileContents = "SECRET_PASS=asdlfkjaølfjaøf".toByteArray()
        given(service.findFileInVault(collectionKey, "secret", "latest.properties")).willReturn(fileContents)

        val vaults = Response(items = listOf(VaultFileResource.fromDecodedBytes(fileContents)))

        mockMvc.get(
            Path(
                "/v1/vault/{vaultCollection}/{vaultKey}/{fileName}",
                collectionKey,
                "secret",
                "latest.properties"
            )
        ) {
            statusIsOk().responseJsonPath("$").equalsObject(vaults)
        }
    }
}
