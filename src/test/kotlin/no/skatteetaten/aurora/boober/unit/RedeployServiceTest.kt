package no.skatteetaten.aurora.boober.unit

import assertk.all
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.mapper.TemplateType
import no.skatteetaten.aurora.boober.service.RedeployService
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.utils.OpenShiftTestDataBuilders.deploymentConfig
import no.skatteetaten.aurora.boober.utils.OpenShiftTestDataBuilders.imageStream
import no.skatteetaten.aurora.boober.utils.OpenShiftTestDataBuilders.imageStreamImportResponse
import org.junit.jupiter.api.Test

class RedeployServiceTest {

    val valaultImageHash = "123"
    val emptyJsonNode = NullNode.getInstance()




    val imageStream = imageStream()
    val deploymentConfig = deploymentConfig()

    val openShiftClient = mockk<OpenShiftClient>()

    val redeployService =
        RedeployService(openShiftClient)

    @Test
    fun `Should not run explicit deploy for development type`() {

        val response = redeployService.triggerRedeploy(listOf(deploymentConfig), TemplateType.development)
        assertThat(response.success).isTrue()
        assertThat(response.message).isEqualTo("No deploy made since type=development, deploy via oc start-build.")
    }

    @Test
    fun `Should throw error if dc is null`() {

        every {
            openShiftClient.performOpenShiftCommand("aos-test", any())
        } returns openShiftResponse()

        assertThat {
            redeployService.triggerRedeploy(listOf(), TemplateType.deploy)
        }.isFailure().all {
            isInstanceOf(IllegalArgumentException::class)
            hasMessage("Missing DeploymentConfig")
        }
    }

    @Test
    fun `Redeploy given null ImageStream return success`() {

        every {
            openShiftClient.performOpenShiftCommand("aos-test", any())
        } returns openShiftResponse()

        val response = redeployService.triggerRedeploy(listOf(deploymentConfig), TemplateType.deploy)

        assertThat(response.success).isTrue()
        assertThat(response.openShiftResponses.size).isEqualTo(1)
    }

    @Test
    fun `Redeploy with paused deploy will run manual deploy`() {
        every {
            openShiftClient.performOpenShiftCommand("aos-test", any())
        } returns imageStreamImportResponse()

        val response =
            redeployService.triggerRedeploy(
                listOf(deploymentConfig("ImageChange", 0), imageStream),
                TemplateType.deploy
            )

        assertThat(response.success).isTrue()
        assertThat(response.message).isEqualTo("No new application version found. Config changes deployment succeeded.")
        assertThat(response.openShiftResponses.size).isEqualTo(1)
    }

    @Test
    fun `Redeploy with newly created imagestream will not import`() {
        every {
            openShiftClient.performOpenShiftCommand("aos-test", any())
        } returns imageStreamImportResponse()

        val response = redeployService.triggerRedeploy(
            listOf(deploymentConfig, imageStream("dockerUrl", OperationType.CREATE)),
            TemplateType.deploy
        )

        assertThat(response.success).isTrue()
        assertThat(response.message).isEqualTo("New application version found.")
        assertThat(response.openShiftResponses.size).isEqualTo(0)
    }

    @Test
    fun `Redeploy given image is already imported return success`() {
        every {
            openShiftClient.performOpenShiftCommand("aos-test", any())
        } returns imageStreamImportResponse()

        val response = redeployService.triggerRedeploy(listOf(deploymentConfig, imageStream), TemplateType.deploy)

        assertThat(response.success).isTrue()
        assertThat(response.openShiftResponses.size).isEqualTo(1)
    }

    @Test
    fun `Redeploy given image is not imported return success`() {
        every {
            openShiftClient.performOpenShiftCommand("aos-test", any())
        } returns imageStreamImportResponse("234")

        val response = redeployService.triggerRedeploy(listOf(deploymentConfig, imageStream), TemplateType.deploy)

        assertThat(response.success).isTrue()
        assertThat(response.openShiftResponses.size).isEqualTo(1)
    }

    @Test
    fun `Redeploy given no type in DeploymentConfig perform deployment request and return success`() {

        val deploymentConfigWithoutType = deploymentConfig(null)

        every {
            openShiftClient.performOpenShiftCommand("aos-test", any())
        } returns openShiftResponse()

        val response =
            redeployService.triggerRedeploy(listOf(deploymentConfigWithoutType, imageStream), TemplateType.deploy)

        assertThat(response.success).isTrue()
        assertThat(response.openShiftResponses.size).isEqualTo(1)
    }

    @Test
    fun `Redeploy with same image will run explicit deploy`() {

        every {
            openShiftClient.performOpenShiftCommand("aos-test", any())
        } returns openShiftResponse()

        val response = redeployService.triggerRedeploy(
            listOf(deploymentConfig, imageStream, imageStreamImportResponse(valaultImageHash)),
            TemplateType.deploy
        )

        assertThat(response.success).isTrue()
        assertThat(response.openShiftResponses.size).isEqualTo(1)
        assertThat(response.message).isEqualTo("No new application version found. Config changes deployment succeeded.")
    }

    @Test
    fun `Redeploy with different image will not run explicit deploy`() {

        val response = redeployService.triggerRedeploy(
            listOf(deploymentConfig, imageStream, imageStreamImportResponse("hash")),
            TemplateType.deploy
        )

        assertThat(response.success).isTrue()
        assertThat(response.openShiftResponses.size).isEqualTo(0)
        assertThat(response.message).isEqualTo("New application version found.")
    }

    fun openShiftResponse(responseBody: JsonNode = emptyJsonNode) =
        OpenShiftResponse(OpenshiftCommand(OperationType.CREATE, "", emptyJsonNode), responseBody)
}
