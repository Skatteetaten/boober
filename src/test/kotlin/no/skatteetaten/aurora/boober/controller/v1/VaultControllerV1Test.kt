package no.skatteetaten.aurora.boober.controller.v1

/*
TODO: fix
@AutoConfigureRestDocs
@WebMvcTest(controllers = [VaultControllerV1::class], secure = false)
class VaultControllerV1Test(@Autowired private val mockMvc: MockMvc) {

    @MockBean
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
        given(service.findAllVaultsWithUserAccessInVaultCollection(collectionKey)).willReturn(allVaults)

        val vaults = given(Response(items = any<List<VaultWithAccess>>().map(::fromVaultWithAccess)))
            .withContractResponse("vault/allvaults") { willReturn(content) }.mockResponse

        mockMvc.get(Path("/v1/vault/{vaultCollection}", collectionKey)) {
            statusIsOk().responseJsonPath("$").equalsObject(vaults)
        }
    }

    @Test
    fun `Get vault in collection`() {
        given(service.findVault(collectionKey, "secret")).willReturn(vault1.vault)

        val vaults = given(Response(items = listOf(any<EncryptedFileVault>()).map(::fromEncryptedFileVault)))
            .withContractResponse("vault/vaults") { willReturn(content) }.mockResponse

        mockMvc.get(Path("/v1/vault/{vaultCollection}/{vaultKey}", collectionKey, "secret")) {
            statusIsOk().responseJsonPath("$").equalsObject(vaults)
        }
    }

    @Test
    fun `Get vault file`() {
        val fileContents = "SECRET_PASS=asdlfkjaølfjaøf".toByteArray()
        given(service.findFileInVault(collectionKey, "secret", "latest.properties")).willReturn(fileContents)

        val vaults = Response(items = listOf(VaultFileResource.fromDecodedBytes(fileContents)))

        // TODO assert header Etag
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

    @Test
    fun `Save vault`() {
        given(service.import(any(), any(), any(), any())).willReturn(vault1.vault)

        val vaults = given(Response(items = listOf(any<EncryptedFileVault>()).map(::fromEncryptedFileVault)))
            .withContractResponse("vault/vaults") { willReturn(content) }.mockResponse

        val payload = AuroraSecretVaultPayload(
            name = "myvault",
            permissions = emptyList(),
            secrets = mapOf("latest.properites" to "U0VDUkVUX1BBU1M9YXNkbGZramHDuGxmamHDuGY=")
        )
        mockMvc.put(
            path = Path("/v1/vault/{vaultCollection}", collectionKey),
            headers = HttpHeaders().contentTypeJson(),
            body = payload
        ) {
            statusIsOk().responseJsonPath("$").equalsObject(vaults)
        }
    }

    @Test
    fun `Delete vault in collection`() {
        mockMvc.delete(Path("/v1/vault/{vaultCollection}/{vaultKey}", collectionKey, "secret")) {
            statusIsOk().responseJsonPath("$.success").isTrue()
        }
    }
}
 */
