package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import java.io.File
import java.nio.charset.Charset
import no.skatteetaten.aurora.boober.controller.security.User
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
import org.apache.commons.text.StringSubstitutor

// TODO: Kan vi lese denne auroraConfigen fra noen filer? Vi har jo noen filer vi bruker i andre tester
abstract class AbstractAuroraConfigTest : ResourceLoader() {

    val AFFILIATION = "aos"

    val DEFAULT_ABOUT = """{
  "schemaVersion": "v1",
  "permissions": {
    "admin": "APP_PaaS_utv"
  },
  "segment" : "aurora",
  "affiliation" : "$AFFILIATION"
}"""

    val DEFAULT_UTV_ABOUT = """{
    "cluster": "utv"
}"""

    // TODO read this from files
    fun defaultAuroraConfig(): MutableMap<String, String> = mutableMapOf(
        "about.json" to DEFAULT_ABOUT,
        "utv/about.json" to DEFAULT_UTV_ABOUT,
        "simple.json" to """{
    "certificate": true,
    "groupId": "ske.aurora.openshift",
    "artifactId": "simple",
    "name": "simple",
    "version": "1.0.3",
    "route": true,
    "type": "deploy"
    }""",
        "utv/simple.json" to """{ }"""
    )

    val DEFAULT_AID = ApplicationDeploymentRef("utv", "simple")

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

    fun getResultFiles(aid: ApplicationDeploymentRef): Map<String, JsonNode?> {
        val baseFolder = File(
            AbstractAuroraConfigTest::class.java
                .getResource("/samples/result/${aid.environment}/${aid.application}").file
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

    val folder = File(AbstractAuroraConfigTest::class.java.getResource("/samples/config").file)

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

    fun createDeployResult(deployId: String, success: Boolean = true): List<AuroraDeployResult> {
        return listOf(
            AuroraDeployResult(
                success = success,
                reason = if (success) "DONE" else "Failed",
                deployCommand = AuroraDeployCommand(
                    headerResources = emptySet(),
                    resources = emptySet(),
                    user = User("hero", "token"),
                    deployId = deployId,
                    shouldDeploy = true,
                    context = createAuroraDeploymentContext()
                )
            )
        )
    }

    fun createAuroraDeploymentContext(): AuroraDeploymentContext {
        return AuroraDeploymentContext(
            spec = createAuroraDeploymentSpec(),
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

    fun createAuroraDeploymentSpec(): AuroraDeploymentSpec {
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
}
