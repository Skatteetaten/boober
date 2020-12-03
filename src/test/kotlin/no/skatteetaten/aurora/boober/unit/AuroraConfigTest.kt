package no.skatteetaten.aurora.boober.unit

import assertk.all
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.messageContains
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigException
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.PreconditionFailureException
import no.skatteetaten.aurora.boober.utils.AuroraConfigSamples.Companion.createAuroraConfig
import no.skatteetaten.aurora.boober.utils.AuroraConfigSamples.Companion.getAuroraConfigSamples
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import org.junit.jupiter.api.Test

class AuroraConfigTest : ResourceLoader() {

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
    fun `Should fail when duplicate field`() {

        val auroraConfig = createAuroraConfig(aid)
        val updates =
            """
            { 
                "version": "2",
                "certificate": false,
                "version": "5"
            }
            """.trimIndent()

        val updateFileResponse = auroraConfig.updateFile("booberdev/console.json", updates)
        val updatedAuroraConfig = updateFileResponse.second

        assertThat {
            updatedAuroraConfig.files
                .filter { it.configName == "booberdev/console.json" }
                .map { it.asJsonNode }
                .first()
        }.isFailure().all {
            isInstanceOf(AuroraConfigException::class)
            messageContains("not valid errorMessage=Duplicate field 'version'")
        }
    }

    @Test
    fun `Should create file when updating nonexisting file`() {

        val auroraConfig = createAuroraConfig(aid)

        val updates = """{ "version": "4"}"""
        val fileName = "boobertest/console.json"

        assertThat(auroraConfig.files.find { it.name == fileName }).isNull()

        val updatedAuroraConfig = auroraConfig.updateFile(fileName, updates).second

        val version = updatedAuroraConfig.files
            .filter { it.configName == fileName }
            .map { it.asJsonNode.get("version").asText() }
            .first()

        assertThat(version).isEqualTo("4")
    }

    @Test
    fun `Returns files for application with include env`() {
        val auroraConfig = getAuroraConfigSamples()
        val filesForApplication = auroraConfig.getFilesForApplication(ApplicationDeploymentRef("utv", "easy"))
        assertThat(filesForApplication.size).isEqualTo(4)
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
    fun `Throw error if baseFile is missing`() {

        val files = createMockFiles("about.json", "utv/simple.json", "utv/about.json")
        val auroraConfig = AuroraConfig(files, "aos", "master")

        assertThat {

            auroraConfig.getFilesForApplication(
                ApplicationDeploymentRef(
                    "utv",
                    "simple"
                )
            )
        }.isFailure().all {
            isInstanceOf(IllegalArgumentException::class)
            hasMessage("Some required AuroraConfig (json|yaml) files missing. BASE file with name simple.")
        }
    }

    @Test
    fun `Should merge with remote and override file`() {
        val auroraConfig = getAuroraConfigSamples()

        val fileName = "utv/simple.json"
        assertThat(auroraConfig.files.find { it.name == fileName }).isNotNull()

        val fileContent = """{ "version" : 1 }"""
        val newAuroraConfig =
            AuroraConfig(listOf(AuroraConfigFile(fileName, contents = fileContent)), auroraConfig.name, "local")

        val merged = auroraConfig.merge(newAuroraConfig)

        assertThat(merged.files.find { it.name == fileName }?.contents).isEqualTo(fileContent)
    }

    @Test
    fun `Should merge with remote and add new file`() {
        val auroraConfig = getAuroraConfigSamples()

        val fileName = "new-app.json"
        assertThat(auroraConfig.files.find { it.name == fileName }).isNull()

        val fileContent = """{ "groupId" : "foo.bar" }"""
        val newAuroraConfig =
            AuroraConfig(listOf(AuroraConfigFile(fileName, contents = fileContent)), auroraConfig.name, "local")

        val merged = auroraConfig.merge(newAuroraConfig)

        assertThat(merged.files.find { it.name == fileName }?.contents).isEqualTo(fileContent)
    }

    @Test
    fun `Should keep existing config if local is empty`() {
        val auroraConfig = getAuroraConfigSamples()

        val newAuroraConfig = AuroraConfig(emptyList(), auroraConfig.name, "local")

        val merged = auroraConfig.merge(newAuroraConfig)

        assertThat(auroraConfig).isEqualTo(merged)
    }

    @Test
    fun `should get error when parsing unkonwn file type`() {

        val auroraConfigFile = AuroraConfigFile(
            name = "foo.yoda",
            contents = """
              replicas:3
              type: "deploy"
              certificate: false""".trimMargin()
        )

        assertThat { auroraConfigFile.asJsonNode }.isFailure()
            .messageContains("Could not parse file with name=foo.yoda")
    }

    @Test
    fun `should get error when parsing yaml file with wrong first line`() {

        val auroraConfigFile = AuroraConfigFile(
            name = "foo.yaml",
            contents = """
              replicas:3
              type: "deploy"
              certificate: false""".trimMargin()
        )

        assertThat { auroraConfigFile.asJsonNode }.isFailure()
            .messageContains("First line in file does not contains space after ':'")
    }

    @Test
    fun `Should fail when adding new file that already exist`() {

        val auroraConfig = createAuroraConfig(aid)
        val updates =
            """
            { 
                "version": "2",
                "certificate": false,
                "version": "5"
            }
            """.trimIndent()

        assertThat {
            auroraConfig.updateFile("about.json", updates)
        }.isFailure().all {
            isInstanceOf(PreconditionFailureException::class)
            messageContains("The fileName=about.json already exists in this AuroraConfig.")
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
