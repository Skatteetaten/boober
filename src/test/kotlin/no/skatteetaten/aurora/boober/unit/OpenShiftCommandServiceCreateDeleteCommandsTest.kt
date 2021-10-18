package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isTrue
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fkorotkov.kubernetes.apps.newDeploymentList
import com.fkorotkov.kubernetes.batch.newCronJobList
import com.fkorotkov.kubernetes.batch.newJobList
import com.fkorotkov.kubernetes.newConfigMapList
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.service.OpenShiftCommandService
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.utils.Instants
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.getResultFiles
import no.skatteetaten.aurora.boober.utils.jsonMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import java.time.Instant

class OpenShiftCommandServiceCreateDeleteCommandsTest : ResourceLoader() {

    val userClient: OpenShiftResourceClient = mockk()
    val saClient: OpenShiftResourceClient = mockk()
    val openShiftClient = OpenShiftClient(userClient, saClient, jsonMapper())
    val openShiftCommandBuilder = OpenShiftCommandService(openShiftClient)

    @BeforeEach
    fun setupTest() {

        clearAllMocks()
        every {
            saClient.getAuthorizationHeaders()
        } returns HttpHeaders()

        every {
            saClient.get("/version", retry = false)
        } returns ResponseEntity.ok(loadJsonResource("response_version.json"))

        every {
            userClient.getAuthorizationHeaders()
        } returns HttpHeaders()
        Instants.determineNow = { Instant.EPOCH }
    }

    @Test
    fun `Should create delete command for all resources with given deployId`() {

        val name = "complex"
        val namespace = "utv"
        val deployId = "abc123"
        val adr = ApplicationDeploymentRef(namespace, name)
        val configMapList = newConfigMapList { }
        val deploymentList = newDeploymentList()
        val jobList = newJobList()
        val cronJobList = newCronJobList()
        val auroraCnameList = mapOf(
            "apiVersion" to "v1",
            "kind" to "AuroraCnameList",
            "items" to ArrayList<String>()
        )
        val responses = createResponsesFromResultFiles(adr)
            .addIfNotNull("configmap" to jsonMapper().convertValue(configMapList))
            .addIfNotNull("deployment" to jsonMapper().convertValue(deploymentList))
            .addIfNotNull("job" to jsonMapper().convertValue(jobList))
            .addIfNotNull("cronjob" to jsonMapper().convertValue(cronJobList))
            .addIfNotNull("auroracname" to mapper.valueToTree<JsonNode>(auroraCnameList))
            .addIfNotNull("auroraazureapp" to mapper.valueToTree<JsonNode>(auroraCnameList))

        responses.forEach {
            val kind = it.key
            val queryString = "labelSelector=app=$name,booberDeployId,booberDeployId!=$deployId"
            val apiUrl = OpenShiftResourceClient.generateUrl(kind, namespace)
            val url = "$apiUrl?$queryString"
            every {
                userClient.get(url, any(), true)
            } returns ResponseEntity.ok(it.value)

            every {
                saClient.get(url, any(), true)
            } returns ResponseEntity.ok(it.value)
        }

        val commands = openShiftCommandBuilder.createOpenShiftDeleteCommands(name, namespace, deployId)

        listOf("BuildConfig", "DeploymentConfig", "ImageStream", "Service", "Secret", "BigIp").forEach {
            assertThat(containsKind(it, commands)).isTrue()
        }
    }

    fun createResponsesFromResultFiles(adr: ApplicationDeploymentRef): Map<String, JsonNode> {

        return adr.getResultFiles().map {
            val responseBody = jsonMapper().createObjectNode()
            val items = jsonMapper().createArrayNode()

            val kind = it.key.split("/")[0]
            val json = it.value!!.content
            val kindList = json.get("kind")?.textValue() + "List"

            items.add(json)
            responseBody.put("kind", kindList)
            responseBody.replace("items", items)

            kind to responseBody
        }.toMap()
    }

    fun containsKind(kind: String, commands: List<OpenshiftCommand>): Boolean {
        return commands.any { cmd ->
            cmd.payload.get("kind")?.let {
                it.textValue() == kind
            } ?: false
        }
    }
}
