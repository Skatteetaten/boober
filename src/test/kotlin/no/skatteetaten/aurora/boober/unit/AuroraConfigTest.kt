package no.skatteetaten.aurora.boober.unit

import assertk.all
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.utils.AbstractAuroraConfigTest
import org.junit.jupiter.api.Test

class AuroraConfigTest : AbstractAuroraConfigTest() {

    val aid = ApplicationDeploymentRef("utv", "simple")

    @Test
    fun `Should get all application ids for AuroraConfig`() {

        val auroraConfig = createAuroraConfig(aid)
        val refs = auroraConfig.getApplicationDeploymentRefs()

        assertThat(refs[0].application).isEqualTo("simple")
        assertThat(refs[0].environment).isEqualTo("utv")
    }

    @Test
    fun `Should update file`() {

        val auroraConfig = createAuroraConfig(aid)
        val updates = """{ "version": "4"}"""

        val updateFileResponse = auroraConfig.updateFile("booberdev/console.json", updates)
        val updatedAuroraConfig = updateFileResponse.second

        val version = updatedAuroraConfig.files
            .filter { it.configName == "booberdev/console.json" }
            .map { it.asJsonNode.get("version").asText() }
            .first()

        assertThat(version).isEqualTo("4")
    }

    @Test
    fun `Should create file when updating nonexisting file`() {

        val auroraConfig = createAuroraConfig(aid)

        val updates = """{ "version": "4"}"""
        val fileName = "boobertest/console.json"

        assertThat(auroraConfig.findFile(fileName)).isNull()

        val updatedAuroraConfig = auroraConfig.updateFile(fileName, updates).second

        val version = updatedAuroraConfig.files
            .filter { it.configName == fileName }
            .map { it.asJsonNode.get("version").asText() }
            .first()

        assertThat(version).isEqualTo("4")
    }

    @Test
    fun `Returns files for application`() {
        val auroraConfig = createAuroraConfig(aid)
        val filesForApplication = auroraConfig.getFilesForApplication(aid)
        assertThat(filesForApplication.size).isEqualTo(4)
    }

    @Test
    fun `Returns files for application with about override`() {
        val auroraConfig = createAuroraConfig(aid)
        val filesForApplication = auroraConfig.getFilesForApplication(aid, listOf(overrideFile("about.json")))
        assertThat(filesForApplication.size).isEqualTo(5)
    }

    @Test
    fun `Returns files for application with app override`() {
        val auroraConfig = createAuroraConfig(aid)
        val filesForApplication = auroraConfig.getFilesForApplication(aid, listOf(overrideFile("simple.json")))
        assertThat(filesForApplication.size).isEqualTo(5)
    }

    @Test
    fun `Returns files for application with app for env override`() {

        val auroraConfig = createAuroraConfig(aid)

        val filesForApplication =
            auroraConfig.getFilesForApplication(aid, listOf(overrideFile("${aid.environment}/${aid.application}.json")))

        assertThat(filesForApplication.size).isEqualTo(5)
    }

    @Test
    fun `Fails when some files for application are missing`() {

        val referanseAid = ApplicationDeploymentRef("utv", "simple")
        val files = createMockFiles("about.json", "simple.json", "utv/about.json")
        val auroraConfig = AuroraConfig(files, "aos", "master")

        assertThat {
            auroraConfig.getFilesForApplication(referanseAid)
        }.isFailure().all {
            isInstanceOf(IllegalArgumentException::class)
            hasMessage("Should find applicationFile utv/simple.(json|yaml)")
        }
    }

    @Test
    fun `Includes base file in files for application when set`() {

        val aid = ApplicationDeploymentRef("utv", "easy")
        val auroraConfig = getAuroraConfigSamples()

        val filesForApplication = auroraConfig.getFilesForApplication(aid)
        assertThat(filesForApplication.size).isEqualTo(4)
        val applicationFile = filesForApplication.find { it.name == "utv/easy.json" }
        val baseFile = applicationFile?.asJsonNode?.get("baseFile")?.textValue()
        assertThat(filesForApplication.any { it.name == baseFile }).isTrue()
    }

    @Test
    fun `Patch file`() {

        val filename = "${aid.environment}/${aid.application}.json"

        val auroraConfigJson = mutableMapOf(
            "about.json" to DEFAULT_ABOUT,
            "utv/about.json" to DEFAULT_UTV_ABOUT,
            filename to """{ "version" : "1.0.0" }}"""
        )
        val auroraConfig = createAuroraConfig(auroraConfigJson)

        val jsonOp = """[{
  "op": "replace",
  "path": "/version",
  "value": "3"
}]
"""

        val version = auroraConfig.files.find { it.name == filename }?.version
        val patchFileResponse = auroraConfig.patchFile(filename, jsonOp, version)
        val patchedAuroraConfig = patchFileResponse.second

        val patchedFile = patchedAuroraConfig.files.find { it.name == filename }
        assertThat(patchedFile?.asJsonNode?.at("/version")?.textValue()).isEqualTo("3")
    }

    @Test
    fun `Throw error if baseFile is missing`() {

        val auroraConfigJson = mutableMapOf(
            "about.json" to DEFAULT_ABOUT,
            "utv/about.json" to DEFAULT_UTV_ABOUT,
            "utv/test.json" to """{ "webseal" : { "roles" : "foobar" }}"""
        )
        val auroraConfig = createAuroraConfig(auroraConfigJson)

        assertThat {

            auroraConfig.getFilesForApplication(
                ApplicationDeploymentRef(
                    "utv",
                    "test"
                )
            )
        }.isFailure().all {
            isInstanceOf(IllegalArgumentException::class)
            hasMessage("Some required AuroraConfig (json|yaml) files missing. BASE file with name test.")
        }
    }

    fun createMockFiles(vararg files: String): List<AuroraConfigFile> {
        return files.map {
            AuroraConfigFile(it, "{}", false, false)
        }
    }

    fun overrideFile(fileName: String) = AuroraConfigFile(
        name = fileName,
        contents = "{}",
        override = true,
        isDefault = false
    )
}
