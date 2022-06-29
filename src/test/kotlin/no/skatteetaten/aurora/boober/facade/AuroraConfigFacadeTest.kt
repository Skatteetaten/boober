package no.skatteetaten.aurora.boober.facade

import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isNotZero
import assertk.assertions.messageContains
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigException
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.service.HerkimerResponse
import no.skatteetaten.aurora.boober.utils.AuroraConfigSamples.Companion.getAuroraConfigSamples
import no.skatteetaten.aurora.boober.utils.configErrors
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class AuroraConfigFacadeTest(
    @Value("\${application.deployment.id}") val booberAdId: String
) : AbstractSpringBootAuroraConfigTest() {

    @Autowired
    lateinit var facade: AuroraConfigFacade

    @BeforeEach
    fun beforeDeploy() {
        preprateTestVault("foo", mapOf("latest.properties" to "FOO=bar\nBAR=baz\n".toByteArray()))

        applicationDeploymentGenerationMock {
            rule({ path.contains("resource?claimedBy=$booberAdId") }) {
                val folder = "$packageName/DeployFacadeTest"
                MockResponse().setBody(loadBufferResource("herkimerResponseBucketAdmin.json", folder))
                    .addHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            }

            rule {
                json(HerkimerResponse<Any>())
            }
        }
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
        assertThat(specList.size).isNotZero()
    }

    @Test
    fun `get spec for applications deployment with override`() {

        val spec: AuroraDeploymentSpec = facade.findAuroraDeploymentSpecSingle(
            ref = auroraConfigRef,
            adr = adr,
            overrideFiles = listOf(
                AuroraConfigFile(
                    "utv/simple.json",
                    override = true,
                    contents = """{ "version" : "foo" }"""
                )
            ),
            errorsAsWarnings = false
        )

        assertThat(spec.get<String>("version")).isEqualTo("foo")
    }

    @Test
    fun `get spec for applications deployment with override that is invalid and swallow errors`() {

        val spec: AuroraDeploymentSpec = facade.findAuroraDeploymentSpecSingle(
            ref = auroraConfigRef,
            adr = ApplicationDeploymentRef("utv", "ah"),
            overrideFiles = listOf(
                AuroraConfigFile(
                    "utv/about.json",
                    override = true,
                    contents = """{ "type" : "deploy" }"""
                )
            ),
            errorsAsWarnings = true
        )

        assertThat(spec.get<String>("type")).isEqualTo("deploy")
    }

    @Test
    fun `get spec for applications deployment with override that is invalid and throw errors`() {

        assertThat {
            facade.findAuroraDeploymentSpecSingle(
                ref = auroraConfigRef,
                adr = ApplicationDeploymentRef("utv", "ah"),
                overrideFiles = listOf(
                    AuroraConfigFile(
                        "utv/about.json",
                        override = true,
                        contents = """{ "type" : "deploy" }"""
                    )
                ),
                errorsAsWarnings = false
            )
        }.configErrors(
            listOf(
                "GroupId must be set and be shorter then 200 characters",
                "/templateFile is not a valid config field pointer",
                "/parameters/FEED_NAME is not a valid config field pointer",
                "/parameters/DOMAIN_NAME is not a valid config field pointer",
                "/parameters/DB_NAME is not a valid config field pointer",
            )
        )
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
        }.singleApplicationError("Config for application simple in environment utv contains errors. Must be one of ")
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
        val newConfig = config.copy(
            files = config.files.filter { it.name != "utv/ah.json" } + AuroraConfigFile(
                "utv/ah.json",
                """
            {
            "config" : {
               "FOO" : "BAR", 
               "BAR" : "BAZ",
            }
        """
            )
        )

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
            .hasMessage("BaseFile ah2.(json|yaml) missing for application: utv/ah2")
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

        assertThat(validated[ApplicationDeploymentRef("utv", "complex")]?.size).isEqualTo(4)
    }

    @Test
    fun `validate duplicated host names `() {

        openShiftMock {

            rule({ path?.endsWith("/groups") }) {
                mockJsonFromFile("groups.json")
            }

            rule({ path?.endsWith("/users") }) {
                mockJsonFromFile("users.json")
            }
        }
        val config = getAuroraConfigSamples()
        val newConfig = config.copy(
            files = config.files.filter { it.name != "utv/simple.json" } + AuroraConfigFile(
                "utv/simple.yaml",
                """
              "bigip" : {
                "service" : "simple-utv",
                "externalHost" :"test.ske",
                "apiPaths": ["/api"]
              }
                """.trimIndent()
            )
        )

        val validated = facade.validateAuroraConfig(
            newConfig,
            resourceValidation = false,
            auroraConfigRef = auroraConfigRef
        )

        val warnings = validated[ApplicationDeploymentRef("utv", "complex")]
        assertThat(warnings?.size).isEqualTo(5)
    }

    @Test
    fun `validate duplicated fqdn host names `() {

        openShiftMock {

            rule({ path?.endsWith("/groups") }) {
                mockJsonFromFile("groups.json")
            }

            rule({ path?.endsWith("/users") }) {
                mockJsonFromFile("users.json")
            }
        }
        val config = getAuroraConfigSamples()
        val newConfig = config.copy(
            files = config.files
                .filter { it.name != "utv/simple.json" }
                .filter { it.name != "utv/whoami.json" } +
                AuroraConfigFile(
                    "utv/simple.json",
                    """
              {
                "route" : {
                    "simple" : {
                        "host" : "foo.bar.baz",
                        "fullyQualifiedHost" : true
                    }
                }
              }
                    """.trimIndent()
                ) +
                AuroraConfigFile(
                    "utv/whoami.json",
                    """
              {
                "route" : {
                    "whoami" : {
                        "host" : "foo.bar.baz",
                        "fullyQualifiedHost" : true
                    }
                }
              }
                    """.trimIndent()
                )
        )

        val validated = facade.validateAuroraConfig(
            newConfig,
            resourceValidation = false,
            auroraConfigRef = auroraConfigRef
        )

        val warnings = validated[ApplicationDeploymentRef("utv", "simple")] ?: emptyList()
        assertThat(warnings.size).isEqualTo(1)
        assertThat(warnings[0]).isEqualTo("The external url=foo.bar.baz is not uniquely defined. Only the last applied configuration will be valid. The following configurations references it=[utv/simple, utv/whoami]")
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
                    .setBody(loadBufferResource("dbhResponse.json", "$packageName/DeployFacadeTest"))
                    .setResponseCode(200)
                    .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            }
        }

        cantusMock {
            rule({ path.endsWith("/manifest") }) {
                val cantusManifestResponseFile = "cantusManifestResponse.json"
                MockResponse()
                    .setBody(loadBufferResource(cantusManifestResponseFile, "$packageName/DeployFacadeTest"))
                    .setResponseCode(200)
                    .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            }
            rule {
                MockResponse()
                    .setBody(""" { "success" : true }""")
                    .setResponseCode(200)
                    .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            }
        }

        bitbucketMock {
            rule {
                mockJsonFromFile(this.requestUrl.pathSegments().last())
            }
        }

        val auroraConfigSamples = getAuroraConfigSamples()
        val validated = facade.validateAuroraConfig(
            auroraConfigSamples,
            resourceValidation = true,
            auroraConfigRef = auroraConfigRef
        )
        assertThat(validated[ApplicationDeploymentRef("utv", "complex")]?.size).isNotNull()
    }

    @Test
    fun `Should fail to update invalid json file with misspelled version`() {

        val fileToChange = "utv/simple.json"
        val theFileToChange = facade.findAuroraConfigFile(auroraConfigRef, fileToChange)

        assertThat {
            facade.updateAuroraConfigFile(
                auroraConfigRef,
                fileToChange,
                """{"vresion": "1.0.0"}""",
                theFileToChange.version
            )
        }.singleApplicationError("/vresion is not a valid config field pointer")
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
    fun `Should update one file in AuroraConfig with deep validation error`() {

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
            """{
                "version": "1.0.0",
                "mounts" : {
                    "foo" : {
                        "path" : "/foo",
                        "type" : "Secret",
                        "exist": true
                     }
                }
}""".trimMargin(),
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

    @Test
    fun `search for applications`() {

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

        val searchForApplications = facade.searchForApplications("master", "utv")
        assertThat(searchForApplications.size).isEqualTo(10)
    }

    @Test
    fun `get spec for including environment files with override`() {

        val spec: AuroraDeploymentSpec = facade.findAuroraDeploymentSpecSingle(
            ref = auroraConfigRef,
            adr = ApplicationDeploymentRef("include", "simple"),
            overrideFiles = listOf(
                AuroraConfigFile(
                    "include/simple.json",
                    override = true,
                    contents = """{}"""
                )
            ),
            errorsAsWarnings = false
        )

        assertThat(spec.get<String>("env/ttl")).isEqualTo("1d")
    }

    @Test
    fun `get spec for replacing globalfile`() {

        val spec: AuroraDeploymentSpec = facade.findAuroraDeploymentSpecSingle(
            ref = auroraConfigRef,
            adr = ApplicationDeploymentRef("include", "include"),
            overrideFiles = listOf(
                AuroraConfigFile(
                    "include/include.json",
                    override = true,
                    contents = """{}"""
                )
            ),
            errorsAsWarnings = false
        )

        assertThat(spec.get<String>("globalFile")).isEqualTo("about-alternative1.json")
    }

    @Test
    fun `Should throw error if globalFile field is placed illegally`() {

        assertThat {
            facade.findAuroraDeploymentSpecSingle(
                ref = auroraConfigRef,
                adr = ApplicationDeploymentRef("include", "include"),
                overrideFiles = listOf(
                    AuroraConfigFile(
                        "include/include.json",
                        override = true,
                        contents = """{ "globalFile": "about-alternative1.json"}"""
                    )
                ),
                errorsAsWarnings = false
            )
        }.isNotNull().isFailure()
            .messageContains("Config for application include in environment include contains errors. Invalid Source field=globalFile. Actual source=include/include.json.override (File type: APP). Must be placed within files of type: [BASE, ENV].")
    }
}
