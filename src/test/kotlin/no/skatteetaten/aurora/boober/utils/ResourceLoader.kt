package no.skatteetaten.aurora.boober.utils

import assertk.Assert
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigField
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldSource
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeployCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraDeployResult
import no.skatteetaten.aurora.boober.service.renderJsonForAuroraDeploymentSpecPointers
import no.skatteetaten.aurora.boober.service.renderSpecAsJson
import okio.Buffer
import org.apache.commons.text.StringSubstitutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.util.ResourceUtils
import java.io.File
import java.net.URL
import java.nio.charset.Charset
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse

private val logger = KotlinLogging.logger {}

open class ResourceLoader {

    val mapper = jsonMapper()

    fun loadResource(resourceName: String, folder: String = this.javaClass.simpleName): String =
        getResourceUrl(resourceName, folder).readText()

    // TODO: should this not use package name to make it easier to reuse files
    fun getResourceUrl(resourceName: String, folder: String = this.javaClass.simpleName): URL {
        val pck = this.javaClass.`package`.name.replace(".", "/")
        val path = "src/test/resources/$pck/$folder/$resourceName"
        return ResourceUtils.getURL(path)
    }

    inline fun <reified T> load(resourceName: String, folder: String = this.javaClass.simpleName): T =
        jacksonObjectMapper().readValue(loadResource(resourceName, folder))

    fun loadJsonResource(resourceName: String, folder: String = this.javaClass.simpleName): JsonNode =
        jacksonObjectMapper().readValue(loadResource(resourceName, folder))

    fun loadByteResource(resourceName: String, folder: String = this.javaClass.simpleName): ByteArray {
        return getResourceUrl(resourceName, folder).openStream().readBytes()
    }

    fun loadBufferResource(resourceName: String, folder: String = this.javaClass.simpleName): Buffer {
        return Buffer().readFrom(getResourceUrl(resourceName, folder).openStream())
    }

    // TODO: test with this method in facade test
    fun Assert<AuroraDeploymentSpec>.auroraDeploymentSpecMatchesSpecFiles(prefix: String): Assert<AuroraDeploymentSpec> =
        transform { spec ->

            val jsonName = "$prefix.json"
            val txtDefaultName = "$prefix-default.txt"
            val jsonDefaultName = "$prefix-default.json"
            val txtName = "$prefix.txt"

            logger.info("comparing default text file=$txtDefaultName")
            assertEquals(loadResource(txtDefaultName), renderJsonForAuroraDeploymentSpecPointers(spec, true))

            logger.info("comparing text file=$txtName")
            assertThat(renderJsonForAuroraDeploymentSpecPointers(spec, false)).isEqualTo(
                loadResource(
                    txtName
                )
            )

            compareJson(
                loadJsonResource(jsonDefaultName),
                mapper.readTree(mapper.writeValueAsString(renderSpecAsJson(spec, true))),
                jsonDefaultName
            )

            compareJson(
                loadJsonResource(jsonName),
                mapper.readTree(mapper.writeValueAsString(renderSpecAsJson(spec, false))),
                jsonName
            )

            spec
        }

    fun Assert<AuroraResource>.auroraResourceMatchesFile(fileName: String): Assert<AuroraResource> = transform { ar ->
        val actualJson: JsonNode = jacksonObjectMapper().convertValue(ar.resource)
        val expectedJson = loadJsonResource(fileName)
        compareJson(expectedJson, actualJson, fileName)
        ar
    }

    // TODO: test with this method in all feature tests
    fun Assert<AuroraDeploymentSpec>.auroraDeploymentSpecMatches(jsonDefaultName: String): Assert<AuroraDeploymentSpec> =
        transform { spec ->
            compareJson(
                loadJsonResource(jsonDefaultName),
                mapper.readTree(mapper.writeValueAsString(renderSpecAsJson(spec, true))),
                jsonDefaultName
            )
            spec
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

fun stubDeployResult(deployId: String, success: Boolean = true, openshiftResponses: List<OpenShiftResponse> = emptyList()): List<AuroraDeployResult> {
    return listOf(
        AuroraDeployResult(
            success = success,
            reason = if (success) "DONE" else "Failed",
            deployCommand = AuroraDeployCommand(
                headerResources = emptySet(),
                resources = emptySet(),
                deployId = deployId,
                shouldDeploy = true,
                context = stubAuroraDeploymentContext()
            ),
            openShiftResponses = openshiftResponses
        )
    )
}

fun stubAuroraDeploymentContext(): AuroraDeploymentContext {
    return AuroraDeploymentContext(
        spec = stubAuroraDeploymentSpec(),
        cmd = AuroraContextCommand(
            auroraConfig = AuroraConfig(
                files = listOf(
                    AuroraConfigFile("about.json", "{}"),
                    AuroraConfigFile("utv/about.json", "{}"),
                    AuroraConfigFile("simple.json", "{}"),
                    AuroraConfigFile("utv/simple.json", "{}")
                ),
                name = "paas",
                ref = "1"
            ),
            applicationDeploymentRef = ApplicationDeploymentRef("utv", "simple"),
            auroraConfigRef = AuroraConfigRef("test", "master", "123")
        ),
        features = emptyMap(),
        featureContext = emptyMap()

    )
}

fun stubAuroraDeploymentSpec(): AuroraDeploymentSpec {
    return AuroraDeploymentSpec(
        fields = mapOf(
            "cluster" to AuroraConfigField(
                sources = setOf(
                    AuroraConfigFieldSource(
                        AuroraConfigFile("about.json", "{}"),
                        TextNode("utv")
                    )
                )
            ),
            "affiliation" to AuroraConfigField(
                sources = setOf(
                    AuroraConfigFieldSource(
                        AuroraConfigFile("about.json", "{}"),
                        TextNode("paas")
                    )
                )
            ),
            "envName" to AuroraConfigField(
                sources = setOf(
                    AuroraConfigFieldSource(
                        AuroraConfigFile("utv/about.json", "{}"),
                        TextNode("utv")
                    )
                )
            ),
            "applicationDeploymentId" to AuroraConfigField(
                sources = setOf(
                    AuroraConfigFieldSource(
                        AuroraConfigFile("static", "{}", isDefault = true),
                        TextNode("1234567890")
                    )
                )
            ),
            "name" to AuroraConfigField(
                sources = setOf(
                    AuroraConfigFieldSource(
                        AuroraConfigFile("utv/simple.json", "{}"),
                        TextNode("simple")
                    )
                )
            ),
            "applicationDeploymentRef" to AuroraConfigField(
                sources = setOf(
                    AuroraConfigFieldSource(
                        AuroraConfigFile("utv/simple.json", "{}"),
                        TextNode("fk1-utv/someApp")
                    )
                )
            )
        ),
        replacer = StringSubstitutor()
    )
}

class AuroraConfigSamples {
    companion object {
        val folder = File(AuroraConfigSamples::class.java.getResource("/samples/config").file)

        fun createAuroraConfig(
            auroraConfigJson: Map<String, String>,

            manualFiles: List<AuroraConfigFile> = emptyList()
        ): AuroraConfig {

            val auroraConfigFiles = auroraConfigJson.map {
                AuroraConfigFile(
                    it.key,
                    it.value
                )
            }

            val manualNames: List<String> = manualFiles.map { it.configName }
            val files = auroraConfigFiles.filterNot { manualNames.contains(it.configName) }

            return AuroraConfig(files + manualFiles, "aos", "master")
        }

        fun createAuroraConfig(
            aid: ApplicationDeploymentRef,
            affiliation: String = "aos",
            additionalFile: String? = null,
            refName: String = "master"
        ): AuroraConfig {
            val files = getSampleFiles(aid, additionalFile)

            return AuroraConfig(files.map {
                AuroraConfigFile(
                    it.key,
                    it.value,
                    false
                )
            }, affiliation, refName)
        }

        fun getAuroraConfigSamples(): AuroraConfig {
            val files = folder.walkBottomUp()
                .onEnter { it.name != "secret" }
                .filter { it.isFile }
                .associate { it.relativeTo(folder).path to it }

            val nodes = files.map {
                it.key to it.value.readText(Charset.defaultCharset())
            }.toMap()

            return AuroraConfig(nodes.map {
                AuroraConfigFile(
                    it.key,
                    it.value,
                    false
                )
            }, "paas", "master", "")
        }

        fun getSampleFiles(aid: ApplicationDeploymentRef, additionalFile: String? = null): Map<String, String> {
            fun collectFiles(vararg fileNames: String): Map<String, String> {

                return fileNames.filter { !it.isBlank() }
                    .associateWith { File(folder, it).readText(Charset.defaultCharset()) }
            }
            return collectFiles(
                "about.json",
                "${aid.application}.json",
                "${aid.environment}/about.json",
                "${aid.environment}/${aid.application}.json",
                additionalFile ?: ""
            )
        }
    }
}

fun ApplicationDeploymentRef.getResultFiles(): Map<String, TestFile?> {
    val baseFolder = File(
        ResourceLoader::class.java
            .getResource("/samples/result/${this.environment}/${this.application}").file
    )

    return baseFolder.listFiles().toHashSet().associate {
        val json = jsonMapper().readTree(it)

        val appName = json.at("/metadata/name").textValue()

        val file = json.at("/kind").textValue() + "/" + appName
        val testFile = TestFile(
            path = it.absolutePath.substringAfter("test/"),
            content = json
        )

        file.toLowerCase() to testFile
    }
}

data class TestFile(
    val path: String,
    val content: JsonNode
)
