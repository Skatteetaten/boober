package no.skatteetaten.aurora.boober.facade

import assertk.all
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newProject
import com.fkorotkov.openshift.status
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.messageContains
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.HerkimerResponse
import no.skatteetaten.aurora.boober.service.MultiApplicationDeployValidationResultException
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import no.skatteetaten.aurora.boober.utils.getResultFiles
import no.skatteetaten.aurora.boober.utils.singleApplicationDeployError
import no.skatteetaten.aurora.boober.utils.singleApplicationValidationError
import okhttp3.mockwebserver.MockResponse

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["integrations.openshift.retries=0", "integrations.s3.variant=storagegrid"]
)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class DeployFacadeTest : AbstractSpringBootAuroraConfigTest() {

    @Autowired
    lateinit var facade: DeployFacade
    private val vaultName = "foo"

    @BeforeEach
    fun beforeDeploy() {
        preprateTestVault(vaultName, mapOf("latest.properties" to "FOO=bar\nBAR=baz\n".toByteArray()))
        mockFionaRequests()
        mockHerkimerRequests()
    }

    @Test
    fun `throw exception when user does not have permission`() {
        assertThat {
            vaultService.import(
                vaultCollectionName = "paas",
                vaultName = vaultName,
                secrets = mapOf("latest.properties" to "".toByteArray()),
                permissions = listOf("NON_EXISTING_PERMISSION")
            )
        }.isFailure().messageContains("do not have required permissions")
    }

    @Test
    fun `throw exception when called with empty permissions`() {
        assertThat {
            vaultService.import(
                vaultCollectionName = "paas",
                vaultName = vaultName,
                secrets = mapOf("latest.properties" to "".toByteArray()),
                permissions = emptyList()
            )
        }.isFailure().messageContains("Public vaults are not allowed")
    }

    @Test
    fun `deploy application when another exist`() {

        val adr = ApplicationDeploymentRef("utv", "easy")
        val resultFiles = adr.getResultFiles()

        skapMock {
            rule {
                MockResponse()
                    .setBody(loadBufferResource("keystore.jks"))
                    .setHeader("key-password", "ca")
                    .setHeader("store-password", "")
            }
        }

        cantusMock {
            rule({ path.endsWith("/manifest") }) {
                val cantusManifestResponseFile = "cantusManifestFailureResponse.json"
                MockResponse()
                    .setBody(loadBufferResource(cantusManifestResponseFile))
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

        dbhMock {
            rule({
                path.contains("application%3Dsimple")
            }) {
                MockResponse()
                    .setBody(loadBufferResource("dbhResponse.json"))
                    .setResponseCode(200)
                    .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            }
        }

        openShiftMock {

            rule({ path?.endsWith("/groups") }) {
                mockJsonFromFile("groups.json")
            }

            rule({ path?.endsWith("/version") }) {
                mockJsonFromFile("response_version.json")
            }

            // Should it be able to reuse rules?
            rule(mockOpenShiftUsers)

            rule({ method == "GET" && path!!.endsWith("aurora-token") || path!!.endsWith("pvc") }) {
                MockResponse().setResponseCode(200)
            }

            // This is an empty environment so no resources exist
            rule({ method == "GET" }) {
                path?.let { it ->
                    if (it.endsWith("/projects/paas-utv")) {
                        json(
                            newProject {
                                metadata {
                                    name = "paas-utv"
                                    labels = mapOf("affiliation" to "paas")
                                }
                                status {
                                    phase = "Active"
                                }
                            }
                        )
                    } else if (it.endsWith("/applicationdeployments/easy")) {
                        val ad = resultFiles["applicationdeployment/easy"]!!.content
                        (ad.at("/metadata") as ObjectNode).replace("uid", TextNode("old-124"))
                        json(ad)
                    } else {
                        val fileName = it.split("/").takeLast(2).joinToString("/").replace("s/", "/")
                        resultFiles[fileName]?.let { file ->
                            json(file.content)
                        } ?: MockResponse().setResponseCode(200)
                    }
                }
            }

            // need to add uid to applicationDeployment for owner reference
            rule({ path?.endsWith("/applicationdeployments/easy") }) {
                replayRequestJsonWithModification(
                    rootPath = "/metadata",
                    key = "uid",
                    newValue = TextNode("123-123")
                )
            }

            rule({ path?.endsWith("/easy-default") }) {
                replayRequestJsonWithModification(
                    rootPath = "",
                    key = "status",
                    newValue = jacksonObjectMapper().readTree(
                        """{ "result": {
      "message": "nothing here",
      "reason": "SGOAProvisioned",
      "success": true
    }}"""
                    )
                )
            }
            // All post/put/delete request just send the result back and assume OK.
            rule {
                MockResponse().setResponseCode(200)
                    .setBody(body)
                    .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            }
        }

        bitbucketMock {
            rule {
                MockResponse().setResponseCode(200).setBody("OK!")
            }
        }

        val result = facade.executeDeploy(auroraConfigRef, listOf(adr))
        assertThat(result).auroraDeployResultMatchesFiles()
    }

    @ParameterizedTest
    @CsvSource(value = ["complex", "whoami", "simple", "web", "ah", "job", "python", "template", "pv"])
    fun `deploy application`(app: String) {

        skapMock {
            rule {
                MockResponse()
                    .setBody(loadBufferResource("keystore.jks"))
                    .setHeader("key-password", "ca")
                    .setHeader("store-password", "")
            }
        }

        dbhMock {
            rule({ method == "GET" && (path.contains("123-456") || path.contains("complex")) }) {
                MockResponse()
                    .setBody(loadBufferResource("dbhResponse.json"))
                    .setResponseCode(200)
                    .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            }

            rule({ method == "POST" }) {
                MockResponse()
                    .setBody(loadBufferResource("dbhResponse.json"))
                    .setResponseCode(200)
                    .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            }

            rule({ method == "GET" }) {
                MockResponse()
                    .setResponseCode(404)
            }
        }

        cantusMock {
            rule({ path.endsWith("/manifest") }) {
                val cantusManifestResponseFile =
                    if (app == "whoami") "cantusManifestResponse.json" else "cantusManifestFailureResponse.json"
                MockResponse()
                    .setBody(loadBufferResource(cantusManifestResponseFile))
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

        openShiftMock {

            rule({ path.contains("storagegrid") }) {
                MockResponse()
                    .setBody(createSgoaResponse(app))
                    .setResponseCode(200)
                    .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            }

            rule({ path?.endsWith("/groups") }) {
                mockJsonFromFile("groups.json")
            }

            rule({ path?.endsWith("/version") }) {
                mockJsonFromFile("response_version.json")
            }

            // Should it be able to reuse rules?
            rule(mockOpenShiftUsers)

            rule({ method == "GET" && path!!.endsWith("aurora-token") || path!!.endsWith("pvc") }) {
                MockResponse().setResponseCode(200)
            }
            // This is an empty environment so no resources exist
            rule({ method == "GET" }) {
                MockResponse().setResponseCode(404)
            }

            // need to add uid to applicationDeployment for owner reference
            rule({ path?.endsWith("/applicationdeployments") }) {
                replayRequestJsonWithModification(
                    rootPath = "/metadata",
                    key = "uid",
                    newValue = TextNode("123-123")
                )
            }

            // All post/put/delete request just send the result back and assume OK.
            rule {
                MockResponse().setResponseCode(200)
                    .setBody(body)
                    .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            }
        }

        bitbucketMock {
            rule({ method == "GET" }) {
                val requestedFile = this.requestUrl.pathSegments().last()
                val templateJson = ResourceLoader().loadResource(requestedFile, "samples/config/templates")
                MockResponse().setBody(templateJson).setResponseCode(200)
            }
            rule {
                MockResponse().setResponseCode(200)
            }
        }

        mattermostMock {
            rule {
                val responseCode = if (app == "complex") 201 else 401
                json("""{}""", responseCode = responseCode)
            }
        }

        val result = kotlin.runCatching {
            facade.executeDeploy(auroraConfigRef, listOf(ApplicationDeploymentRef("utv", app)))
        }.onFailure {
            when (it) {
                is MultiApplicationDeployValidationResultException -> println(it.toValidationErrors())
            }
            throw it
        }.getOrThrow()

        assertThat(result.first().auroraDeploymentSpecInternal).auroraDeploymentSpecMatchesSpecFiles("$app-spec")
        assertThat(result).auroraDeployResultMatchesFiles()

        if (app == "whoami") {
            result.forEach {
                assertThat(it.reason).isNotNull()
                    .contains("Failed to send notification")
            }
        }
        if (app == "complex") {
            result.forEach {
                assertThat(it.reason).isNotNull()
                    .doesNotContain("Failed to send notification")
            }

            assertThat(result.first().warnings).all {
                contains("Both Webseal-route and OpenShift-Route generated for application. If your application relies on WebSeal security this can be harmful! Set webseal/strict to false to remove this warning.")
                contains("Both sts and certificate feature has generated a cert. Turn off certificate if you are using the new STS service")
                contains("The property 'connection' on alerts is deprecated. Please use the connections property")
                contains("Both Webseal-route and Azure-Route generated for application. If your application relies on WebSeal security this can be harmful! Set webseal/strict to false to remove this warning.")
                contains("Config key=THIS.VALUE was normalized to THIS_VALUE")
                contains("Was unable to resolve dockerDigest for image=docker.registry:5000/no_skatteetaten_aurora/clinger:0. Using tag instead.")
                contains("Was unable to resolve dockerDigest for image=docker.registry:5000/fluent/fluent-bit:1.6.10. Using tag instead.")
                hasSize(7)
            }
        } else {
            assertThat(result.first().warnings.isEmpty())
        }
    }

    @Test
    fun `fail if no application deployment ref`() {
        assertThat { facade.executeDeploy(auroraConfigRef, emptyList()) }.isFailure()
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `fail deploy of application in different cluster`() {

        openShiftMock {

            rule({ path?.endsWith("/groups") }) {
                mockJsonFromFile("groups.json")
            }

            // Should it be able to reuse rules?
            rule(mockOpenShiftUsers)
        }

        assertThat {
            facade.executeDeploy(
                auroraConfigRef, listOf(ApplicationDeploymentRef("utv", "simple")),
                overrides = listOf(
                    AuroraConfigFile(
                        "utv/about.json",
                        contents = """{ "cluster" : "test" }""",
                        override = true
                    )
                )
            )
        }.singleApplicationValidationError("Not valid in this cluster")
    }

    @Test
    fun `fail deploy of application if unused override file`() {

        openShiftMock {

            rule({ path?.endsWith("/groups") }) {
                mockJsonFromFile("groups.json")
            }

            // Should it be able to reuse rules?
            rule(mockOpenShiftUsers)
        }

        assertThat {
            facade.executeDeploy(
                auroraConfigRef, listOf(ApplicationDeploymentRef("utv", "simple")),
                overrides = listOf(
                    AuroraConfigFile(
                        "utv/foobar.json",
                        contents = """{ "version" : "test" }""",
                        override = true
                    )
                )
            )
        }.isFailure()
            .messageContains("Overrides files 'utv/foobar.json' does not apply to any deploymentReference (utv/simple)")
    }

    @Test
    fun `fail deploy if there are duplicate resources generated`() {

        val env = "utv"
        val namespace = "paas-$env"

        dbhMock {
            rule {
                MockResponse()
                    .setBody(loadBufferResource("dbhResponse.json"))
                    .setResponseCode(200)
                    .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            }
        }

        openShiftMock {

            getRule("/groups", "groups.json")
            getRule("/projects/$namespace", "project.json")
            getRule("/namespaces/$namespace", "namespace.json")
            putRule("/namespaces/$namespace")

            getRule("/namespaces/$namespace/rolebindings/admin", statusCode = 404)
            postRule("/namespaces/$namespace/rolebindings")

            // Should it be able to reuse rules?
            rule(mockOpenShiftUsers)
        }

        assertThat {
            facade.executeDeploy(
                auroraConfigRef, listOf(ApplicationDeploymentRef(env, "ah")),
                overrides = listOf(
                    AuroraConfigFile(
                        "$env/ah.json",
                        contents = """{ "route" : "true" }""",
                        override = true
                    )
                )
            )
        }.singleApplicationDeployError("The following resources are generated more then once route/ah")
    }

    private fun mockHerkimerRequests() {
        val adId = "1234567890"
        applicationDeploymentGenerationMock(adId) {
            rule({ path.contains("resource?claimedBy=$adId") }) {
                MockResponse()
                    .setBody(loadBufferResource("herkimerResponseBucketAdminSG.json"))
                    .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            }

            rule {
                json(HerkimerResponse<Any>())
            }
        }
    }

    private fun mockFionaRequests() {
        fionaMock {
            rule {
                MockResponse()
                    .setBody(loadBufferResource("fionaResponse.json"))
                    .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            }
        }
    }

    private fun createSgoaResponse(app: String): String = """
   { 
      "apiVersion" : "skatteetaten.no/v1",
      "kind" : "StorageGridObjectArea",
      "metadata" : {
        "labels" : {
          "operationScope" : "aos-4016",
          "app" : "$app",
          "updatedBy" : "hero",
          "updatedAt" : "0",
          "lastUpdatedYear" : "1970",
          "affiliation" : "paas",
          "name" : "$app",
          "booberDeployId" : "deploy1"
        },
        "name" : "$app-default",
        "namespace" : "paas-utv",
        "ownerReferences" : [ {
          "apiVersion" : "skatteetaten.no/v1",
          "kind" : "ApplicationDeployment",
          "name" : "$app",
          "uid" : "123-123"
        } ]
      },
      "spec" : {
        "bucketPostfix" : "mybucket",
        "applicationDeploymentId" : "1234567890",
        "objectArea" : "default",
        "tryReuseCredentials" : false
      },
      "status" : {
        "result" : {
          "message" : "nothing here",
          "reason" : "SGOAProvisioned",
          "success" : true
        }
      }
   }
    """.trimIndent()
}
