package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef.Companion.adr
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import java.io.File

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


    fun defaultAuroraConfig(): MutableMap<String, String> = mutableMapOf(
        "about.json" to DEFAULT_ABOUT,
        "utv/about.json" to DEFAULT_UTV_ABOUT,
        "aos-simple.json" to """{
    "certificate": true,
    "groupId": "ske.aurora.openshift",
    "artifactId": "aos-simple",
    "name": "aos-simple",
    "version": "1.0.3",
    "route": true,
    "type": "deploy"
    }""",
        "utv/aos-simple.json" to """{ }"""
    )

    val DEFAULT_AID = adr("utv", "aos-simple")

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
            AuroraConfigHelper::class.java
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
}
