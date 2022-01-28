package no.skatteetaten.aurora.boober.facade

import java.io.File
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import assertk.Assert
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.AuroraDeployResult
import no.skatteetaten.aurora.boober.service.vault.VaultService
import no.skatteetaten.aurora.boober.utils.AuroraConfigSamples.Companion.getAuroraConfigSamples
import no.skatteetaten.aurora.boober.utils.UUIDGenerator
import no.skatteetaten.aurora.boober.utils.getResultFiles
import no.skatteetaten.aurora.boober.utils.jsonMapper
import no.skatteetaten.aurora.boober.utils.openshiftKind
import no.skatteetaten.aurora.boober.utils.recreateFolder
import no.skatteetaten.aurora.boober.utils.recreateRepo
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.MockRules

private val logger = KotlinLogging.logger {}

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

    fun Assert<List<AuroraDeployResult>>.auroraDeployResultMatchesFiles() = transform { ar ->
        assertThat(ar.size).isEqualTo(1)
        val auroraDeployResult = ar.first()
        assertThat(auroraDeployResult.success).isTrue()

        val generatedObjects = auroraDeployResult.openShiftResponses.mapNotNull {
            it.responseBody
        }
        val resultFiles = auroraDeployResult.command.applicationDeploymentRef.getResultFiles()
        val keys = resultFiles.keys

        generatedObjects.forEach { generatedObject ->
            val key = generatedObject.getKey()
            assertThat(keys).contains(key)

            if (generatedObject.openshiftKind == "secret") {
                val data = generatedObject["data"] as ObjectNode
                data.fields().forEach { (key, _) ->
                    data.put(key, "REMOVED_IN_TEST")
                }
            } else if (generatedObject.openshiftKind == "applicationdeployment") {
                val auroraConfigField = generatedObject.at("/spec/command/auroraConfig") as ObjectNode
                auroraConfigField.replace("resolvedRef", TextNode("123abb"))
            }
            val resultFile = resultFiles[key]!!
            val name = resultFile.path.substringAfterLast("/")
            val path = resultFile.path.substringBeforeLast("/")

            assertThat(resultFile.content).jsonEquals(expected = generatedObject, name = name, folder = path)
            // compareJson(resultFile.content, generatedObject, resultFile.path)
        }
        val generatedObjectNames = generatedObjects.map { it.getKey() }.toSortedSet()
        val expected = resultFiles.keys.toSortedSet()
        assertThat(generatedObjectNames).isEqualTo(expected)
    }
}

// This is done as text comparison and not jsonNode equals to get easier diff when they dif
fun compareJson(expected: JsonNode, actual: JsonNode, name: String? = null): Boolean {
    val writer = jsonMapper().writerWithDefaultPrettyPrinter()
    val targetString = writer.writeValueAsString(actual)
    val nodeString = writer.writeValueAsString(expected)

    name?.let {
        logger.info { "Comparing file with name=$name" }
    }

    assertThat(targetString).isEqualTo(nodeString)
    return true
}

fun JsonNode.getKey(): String {
    val kind = this.get("kind").asText().lowercase()
    val metadata = this.get("metadata")

    val name = if (metadata == null) {
        this.get("name").asText().lowercase()
    } else {
        metadata.get("name").asText().lowercase()
    }

    return "$kind/$name"
}
