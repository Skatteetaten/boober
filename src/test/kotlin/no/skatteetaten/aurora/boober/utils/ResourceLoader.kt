package no.skatteetaten.aurora.boober.utils

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.net.URL
import java.nio.charset.Charset
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigField
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldSource
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeployCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraDeployResult
import okio.Buffer
import org.apache.commons.text.StringSubstitutor
import org.springframework.util.ResourceUtils

open class ResourceLoader {

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
}

// This is done as text comparison and not jsonNode equals to get easier diff when they dif
fun compareJson(expected: JsonNode, actual: JsonNode, name: String? = null): Boolean {
    val writer = jsonMapper().writerWithDefaultPrettyPrinter()
    val targetString = writer.writeValueAsString(actual)
    val nodeString = writer.writeValueAsString(expected)
    assertThat(targetString, name).isEqualTo(nodeString)
    return true
}

fun stubDeployResult(deployId: String, success: Boolean = true): List<AuroraDeployResult> {
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
            )
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
                version = "1"
            ),
            applicationDeploymentRef = ApplicationDeploymentRef("utv", "simple"),
            auroraConfigRef = AuroraConfigRef("test", "master", "123")
        ),
        features = emptyMap(),
        featureHandlers = emptyMap()

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
            }, "paas", "master")
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
                additionalFile?.let { it } ?: ""
            )
        }
    }
}

fun ApplicationDeploymentRef.getResultFiles(): Map<String, JsonNode?> {
    val baseFolder = File(
        ResourceLoader::class.java
            .getResource("/samples/result/${this.environment}/${this.application}").file
    )

    return baseFolder.listFiles().toHashSet().associate {
        val json = jsonMapper().readTree(it)

        var appName = json.at("/metadata/name").textValue()
        if ("".isNotBlank()) {
            appName = ""
        }

        val file = json.at("/kind").textValue() + "/" + appName
        file.toLowerCase() to json
    }
}
