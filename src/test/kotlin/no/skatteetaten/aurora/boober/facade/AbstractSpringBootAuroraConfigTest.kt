package no.skatteetaten.aurora.boober.facade

import assertk.Assert
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import java.io.File
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.AuroraDeployResult
import no.skatteetaten.aurora.boober.service.vault.VaultService
import no.skatteetaten.aurora.boober.utils.AuroraConfigSamples.Companion.getAuroraConfigSamples
import no.skatteetaten.aurora.boober.utils.UUIDGenerator
import no.skatteetaten.aurora.boober.utils.compareJson
import no.skatteetaten.aurora.boober.utils.getResultFiles
import no.skatteetaten.aurora.boober.utils.openshiftKind
import no.skatteetaten.aurora.boober.utils.recreateFolder
import no.skatteetaten.aurora.boober.utils.recreateRepo
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value

/*

  If your tests needs access to auroraConfig use the method in BeforeEach
   - prepareTestAuroraConfig to setup an AuroraConfig repo
   - use the auroraConfigRef variable to point to this AuroraConfig

  In order to mock http call use one of the mock methods
   - openShiftMock
   - skapMock
   - bitbucketMock
   - cantusMock
 */
abstract class AbstractSpringBootAuroraConfigTest : AbstractSpringBootTest() {

    val mockOpenShiftUsers = MockRules({ path?.endsWith("/users") }, { mockJsonFromFile("users.json") })

    @Value("\${integrations.aurora.config.git.repoPath}")
    lateinit var auroraConfigCrepoPath: String

    @Value("\${integrations.aurora.config.git.checkoutPath}")
    lateinit var auroraConfigCheckoutPath: String

    @Value("\${integrations.aurora.vault.git.repoPath}")
    lateinit var vaultRepoPath: String

    @Value("\${integrations.aurora.vault.git.checkoutPath}")
    lateinit var vaultCheckoutPath: String

    @Autowired
    lateinit var auroraConfigService: AuroraConfigService

    @Autowired
    lateinit var vaultService: VaultService

    fun preprateTestVault(vaultName: String, secrets: Map<String, ByteArray>) {
        recreateRepo(File(vaultRepoPath, "${auroraConfigRef.name}.git"))
        recreateFolder(File(vaultCheckoutPath))

        vaultService.import(
            vaultCollectionName = auroraConfigRef.name,
            vaultName = vaultName,
            secrets = secrets,
            permissions = listOf("APP_PaaS_utv")
        )

        /*
        val vault= vaultService.findFileInVault(auroraConfigRef.name, vaultName, "latest.properties")
        val props=PropertiesLoaderUtils
            .loadProperties(ByteArrayResource(vault))
        logger.info{props}
         */
    }

    fun prepareTestAuroraConfig(config: AuroraConfig = getAuroraConfigSamples()) {
        recreateRepo(File(auroraConfigCrepoPath, "${config.name}.git"))
        recreateFolder(File(auroraConfigCheckoutPath))
        auroraConfigService.save(config)
    }

    @BeforeEach
    fun beforeConfigTest() {
        prepareTestAuroraConfig()
        UUIDGenerator.generateId = { "deploy1" }
    }
}

fun Assert<List<AuroraDeployResult>>.auroraDeployResultMatchesFiles() = transform { ar ->
    assertThat(ar.size).isEqualTo(1)
    val auroraDeployResult = ar.first()
    assertThat(auroraDeployResult.success).isTrue()

    val generatedObjects = auroraDeployResult.openShiftResponses.mapNotNull {
        it.responseBody
    }
    val resultFiles = auroraDeployResult.command.applicationDeploymentRef.getResultFiles()
    val keys = resultFiles.keys

    generatedObjects.forEach {
        val key: String = it.getKey()
        assertk.assertThat(keys).contains(key)
        if (it.openshiftKind == "secret") {
            val data = it["data"] as ObjectNode
            data.fields().forEach { (key, _) ->
                data.put(key, "REMOVED_IN_TEST")
            }
        } else if (it.openshiftKind == "applicationdeployment") {
            val auroraConfigField = it.at("/spec/command/auroraConfig") as ObjectNode
            auroraConfigField.set("resolvedRef", TextNode("123abb"))
        }
        compareJson(resultFiles[key]!!, it)
    }
    assertk.assertThat(generatedObjects.map { it.getKey() }.toSortedSet()).isEqualTo(resultFiles.keys.toSortedSet())
}

fun JsonNode.getKey(): String {
    val kind = this.get("kind").asText().toLowerCase()
    val metadata = this.get("metadata")

    val name = if (metadata == null) {
        this.get("name").asText().toLowerCase()
    } else {
        metadata.get("name").asText().toLowerCase()
    }

    return "$kind/$name"
}
