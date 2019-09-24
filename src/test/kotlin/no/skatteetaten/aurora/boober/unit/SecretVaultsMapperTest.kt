package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNullOrEmpty
import no.skatteetaten.aurora.boober.utils.AbstractAuroraConfigTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class SecretVaultsMapperTest : AbstractAuroraConfigTest() {

    /* TODO: fix
    lateinit var auroraConfigJson: MutableMap<String, String>

    @BeforeEach
    fun setup() {
        auroraConfigJson = defaultAuroraConfig()
    }

    data class SecretVaultTestData(
        val configFile: String,
        val vaultName: String,
        val keys: List<String>
    )

    enum class SecretVaultTestDataEnum(val vault: SecretVaultTestData) {
        CONFIGFILE_STRING(
            SecretVaultTestData(
                """{ "secretVault": "vaultName" }""",
                "vaultName",
                emptyList()
            )
        ),
        CONFIGFILE_OBJECT(
            SecretVaultTestData(
                """{ "secretVault": {"name": "test"} }""",
                "test",
                emptyList()
            )
        ),
        CONFIGFILE_EMPTY_KEYS(
            SecretVaultTestData(
                """{ "secretVault": {"name": "test", "keys": []} }""",
                "test",
                emptyList()
            )
        ),
        WITH_KEYS(
            SecretVaultTestData(
                """{ "secretVault": {"name": "test", "keys": ["test1", "test2"]} }""",
                "test",
                listOf("test1", "test2")
            )
        ),
        WITH_KEYMAPPINGS(
            SecretVaultTestData(
                """{ "secretVault": {"name": "test", "keys": ["test1"], "keyMappings":{"test1":"newtestkey"}} }""",
                "test",
                listOf("test1")
            )
        ),
        NEW_SYNTAX(
            SecretVaultTestData(
                """{ "secretVaults": { "test" :{} }}""",
                "test",
                emptyList()
            )
        ),
        NEW_SYNTAX_WITH_REPLACER(
            SecretVaultTestData(
                """{ "secretVaults": { "@name@" :{} }}""",
                "aos-simple",
                emptyList()
            )
        ),
    }

    @ParameterizedTest
    @EnumSource(SecretVaultTestDataEnum::class)
    fun `Parses variants of secretVault config correctly`(testData: SecretVaultTestDataEnum) {

        auroraConfigJson["utv/aos-simple.json"] = testData.vault.configFile

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        assertThat(deploymentSpec.volume?.secrets?.get(0)?.secretVaultKeys).isEqualTo(testData.vault.keys)
        assertThat(deploymentSpec.volume?.secrets?.get(0)?.secretVaultName).isEqualTo(testData.vault.vaultName)
    }

    @Test
    fun `skip vault that is not enabled`() {
        auroraConfigJson["utv/aos-simple.json"] = """{ "secretVaults": { "test" : { "enabled" : false }}}"""
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
        assertThat(deploymentSpec.volume?.secrets).isNullOrEmpty()
    }

     */
}
