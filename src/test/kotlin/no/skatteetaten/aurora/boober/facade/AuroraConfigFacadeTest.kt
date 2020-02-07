package no.skatteetaten.aurora.boober.facade

import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.messageContains
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigException
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.utils.AuroraConfigSamples.Companion.getAuroraConfigSamples
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class AuroraConfigFacadeTest : AbstractSpringBootAuroraConfigTest() {

    @Autowired
    lateinit var facade: AuroraConfigFacade

    @BeforeEach
    fun beforeDeploy() {
        preprateTestVault("foo", mapOf("latest.properties" to "FOO=bar\nBAR=baz\n".toByteArray()))
    }

    val adr = ApplicationDeploymentRef("utv", "simple")

    @Test
    fun `get spec for applications deployment refs`() {
        val specList = facade.findAuroraDeploymentSpec(auroraConfigRef, listOf(adr))
        assertThat(specList.size).isEqualTo(1)
        val spec = specList.first()
        assertThat(spec).isNotNull()
    }

    @Test
    fun `get spec for environment utv`() {

        val specList = facade.findAuroraDeploymentSpecForEnvironment(auroraConfigRef, "utv")
        assertThat(specList.size).isEqualTo(5)
    }

    @Test
    fun `get spec for applications deployment with override`() {

        val spec: AuroraDeploymentSpec = facade.findAuroraDeploymentSpecSingle(
            auroraConfigRef, adr,
            listOf(AuroraConfigFile("utv/simple.json", override = true, contents = """{ "version" : "foo" }"""))
        )

        assertThat(spec.get<String>("version")).isEqualTo("foo")
    }

    @Test
    fun `get config files for application`() {

        val files = facade.findAuroraConfigFilesForApplicationDeployment(auroraConfigRef, adr)
        assertThat(files.size).isEqualTo(4)
    }

    @Test
    fun `get all config files`() {
        val files = facade.findAuroraConfig(auroraConfigRef).files
        assertThat(files).isNotEmpty()
    }

    @Test
    fun `get all config filenames`() {
        val files = facade.findAuroraConfigFileNames(auroraConfigRef)
        assertThat(files).isNotEmpty()
    }

    @Test
    fun `should get error if auroraconfig file is not found`() {
        assertThat {
            facade.findAuroraConfigFile(auroraConfigRef, "utv/simple2.json")
        }.isFailure().messageContains("No such file")
    }

    @Test
    fun `find auroraconfig file`() {
        val file = facade.findAuroraConfigFile(auroraConfigRef, "utv/simple.json")
        assertThat(file).isNotNull()
    }

    @Test
    fun `validate and merge remote aurora config `() {

        openShiftMock {

            rule({ path?.endsWith("/groups") }) {
                mockJsonFromFile("groups.json")
            }

            rule({ path?.endsWith("/users") }) {
                mockJsonFromFile("users.json")
            }
        }

        val localAuroraConfig = AuroraConfig(
            files = listOf(AuroraConfigFile("utv/simple.json", """{ "type" : "foobar" }""")),
            name = "paas",
            ref = "local"
        )

        assertThat {
            facade.validateAuroraConfig(
                localAuroraConfig,
                resourceValidation = false,
                auroraConfigRef = auroraConfigRef,
                mergeWithRemoteConfig = true
            )
        }.singleApplicationError("Config for application simple in environment utv contains errors. Must be one of [deploy, development, localTemplate, template].")
    }

    @Test
    fun `validate should fail if duplicate fileName with different extension`() {

        openShiftMock {

            rule({ path?.endsWith("/groups") }) {
                mockJsonFromFile("groups.json")
            }

            rule({ path?.endsWith("/users") }) {
                mockJsonFromFile("users.json")
            }
        }
        val config = getAuroraConfigSamples()
        val newConfig = config.copy(files = config.files + AuroraConfigFile("utv/ah.yaml", "---"))

        assertThat {
            facade.validateAuroraConfig(
                newConfig,
                resourceValidation = false,
                auroraConfigRef = auroraConfigRef
            )
        }.isFailure()
            .isInstanceOf(AuroraDeploymentSpecValidationException::class)
            .hasMessage("The following files are ambigious [utv/ah.json, utv/ah.yaml]")
    }

    @Test
    fun `validate should fail if dangling comma in file`() {

        openShiftMock {

            rule({ path?.endsWith("/groups") }) {
                mockJsonFromFile("groups.json")
            }

            rule({ path?.endsWith("/users") }) {
                mockJsonFromFile("users.json")
            }
        }
        val config = getAuroraConfigSamples()
        val newConfig = config.copy(files = config.files.filter { it.name != "utv/ah.json" } + AuroraConfigFile(
            "utv/ah.json", """
            {
            "config" : {
               "FOO" : "BAR", 
               "BAR" : "BAZ",
            }
        """
        ))

        assertThat {
            facade.validateAuroraConfig(
                newConfig,
                resourceValidation = false,
                auroraConfigRef = auroraConfigRef
            )
        }.isFailure()
            .isInstanceOf(AuroraConfigException::class)
            .messageContains(" AuroraConfigFile=utv/ah.json is not valid errorMessage=Unexpected character ('}'")
    }

    @Test
    fun `validate should fail if file has dangling application file `() {

        openShiftMock {

            rule({ path?.endsWith("/groups") }) {
                mockJsonFromFile("groups.json")
            }

            rule({ path?.endsWith("/users") }) {
                mockJsonFromFile("users.json")
            }
        }
        val config = getAuroraConfigSamples()
        val newConfig = config.copy(files = config.files + AuroraConfigFile("utv/ah2.yaml", "---"))

        assertThat {
            facade.validateAuroraConfig(
                newConfig,
                resourceValidation = false,
                auroraConfigRef = auroraConfigRef
            )
        }.isFailure()
            .isInstanceOf(IllegalArgumentException::class)
            .hasMessage("Some required AuroraConfig (json|yaml) files missing. BASE file with name ah2.")
    }

    @Test
    fun `validate sample aurora config `() {

        openShiftMock {

            rule({ path?.endsWith("/groups") }) {
                mockJsonFromFile("groups.json")
            }

            rule({ path?.endsWith("/users") }) {
                mockJsonFromFile("users.json")
            }
        }

        val validated = facade.validateAuroraConfig(
            getAuroraConfigSamples(),
            resourceValidation = false,
            auroraConfigRef = auroraConfigRef
        )

        assertThat(validated[ApplicationDeploymentRef("utv", "complex")]?.size).isEqualTo(2)
    }

    @Test
    fun `validate sample aurora config full`() {

        openShiftMock {

            rule({ path?.endsWith("/groups") }) {
                mockJsonFromFile("groups.json")
            }

            rule({ path?.endsWith("/users") }) {
                mockJsonFromFile("users.json")
            }

            rule({ method == "GET" && path!!.endsWith("aurora-token") || path!!.endsWith("pvc") }) {
                MockResponse().setResponseCode(200)
            }
        }

        dbhMock {
            rule {
                MockResponse()
                    .setBody(loadBufferResource("dbhResponse.json", "DeployFacadeTest"))
                    .setResponseCode(200)
                    .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            }
        }

        val validated = facade.validateAuroraConfig(
            getAuroraConfigSamples(),
            resourceValidation = true,
            auroraConfigRef = auroraConfigRef
        )
        assertThat(validated[ApplicationDeploymentRef("utv", "complex")]?.size).isNotNull()
    }

    @Test
    fun `Should fail to update invalid json file`() {

        val fileToChange = "utv/simple.json"
        val theFileToChange = facade.findAuroraConfigFile(auroraConfigRef, fileToChange)

        assertThat {
            facade.updateAuroraConfigFile(
                auroraConfigRef,
                fileToChange,
                """foo {"version": "1.0.0"}""",
                theFileToChange.version
            )
        }.isFailure().messageContains("utv/simple.json is not valid")
    }

    @Test
    fun `Should update one file in AuroraConfig`() {

        openShiftMock {

            rule({ path?.endsWith("/groups") }) {
                mockJsonFromFile("groups.json")
            }

            rule({ path?.endsWith("/users") }) {
                mockJsonFromFile("users.json")
            }
        }

        val fileToChange = "utv/simple.json"
        val theFileToChange = facade.findAuroraConfigFile(auroraConfigRef, fileToChange)

        val file = facade.updateAuroraConfigFile(
            auroraConfigRef,
            fileToChange,
            """{"version": "1.0.0"}""",
            theFileToChange.version
        )

        assertThat(file).isNotNull()
        val json: JsonNode = jacksonObjectMapper().readTree(file.contents)
        assertThat(json.at("/version").textValue()).isEqualTo("1.0.0")
    }

    @Test
    fun `Should not update one file in AuroraConfig if version is wrong`() {

        val fileToChange = "utv/simple.json"

        assertThat {
            facade.updateAuroraConfigFile(
                auroraConfigRef,
                fileToChange,
                """{"version": "1.0.0"}""",
                "incorrect hash"
            )
        }.isNotNull().isFailure()
            .messageContains("The provided version of the current file (incorrect hash) in AuroraConfig paas is not correct")
    }

    @Test
    fun `Should patch auroraConfigFile`() {
        openShiftMock {

            rule({ path?.endsWith("/groups") }) {
                mockJsonFromFile("groups.json")
            }

            rule({ path?.endsWith("/users") }) {
                mockJsonFromFile("users.json")
            }
        }

        val patch = """[{
            "op": "add",
            "path": "/version",
            "value": "test"
        }]"""

        val filename = "utv/simple.json"
        val findAuroraConfigFile = facade.findAuroraConfigFile(auroraConfigRef, filename)

        val result = facade.patchAuroraConfigFile(
            ref = auroraConfigRef,
            filename = filename,
            jsonPatchOp = patch
        )

        assertThat(result).isNotNull()
        val json: JsonNode = jacksonObjectMapper().readTree(result.contents)
        assertThat(json.at("/version").textValue()).isEqualTo("test")
    }

    @Test
    fun `find all auroraConfig names`() {

        bitbucketMock {
            rule {

                val json = mapOf("values" to listOf(mapOf("slug" to "paas")))
                MockResponse()
                    .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .setResponseCode(200)
                    .setBody(
                        jacksonObjectMapper()
                            .writeValueAsString(json)
                    )
            }
        }
        val names = facade.findAllAuroraConfigNames()
        assertThat(names.first()).isEqualTo("paas")
    }
}
