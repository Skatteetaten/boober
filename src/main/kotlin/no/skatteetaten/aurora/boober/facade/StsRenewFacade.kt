package no.skatteetaten.aurora.boober.facade

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.OwnerReference
import no.skatteetaten.aurora.boober.feature.StsSecretGenerator
import no.skatteetaten.aurora.boober.service.OpenShiftCommandService
import no.skatteetaten.aurora.boober.service.RedeployService
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioner
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.whenTrue

@Service
@ConditionalOnProperty("integrations.skap.url")
class StsRenewFacade(
    val sts: StsProvisioner,
    val commandService: OpenShiftCommandService,
    val openshiftClient: OpenShiftClient,
    val redeployer: RedeployService,
    val userDetailsProvider: UserDetailsProvider
) {

    fun renew(request: RenewRequest): List<OpenShiftResponse> {

        val stsResult = sts.generateCertificate(
            cn = request.commonName,
            name = request.name,
            envName = request.namespace
        )

        val labels = mapOf(
            "app" to request.name,
            "updatedBy" to userDetailsProvider.getAuthenticatedUser().username.replace(":", "-"),
            "affiliation" to request.affiliation
        )

        val secret = StsSecretGenerator.create(
            appName = request.name,
            stsProvisionResults = stsResult,
            labels = labels,
            ownerReference = request.ownerReference,
            namespace = request.namespace,
            suffix = request.suffix
        )
        val json = jacksonObjectMapper()
            .convertValue<JsonNode>(secret)
        val command = commandService.createOpenShiftCommand(request.namespace, json)

        val response = openshiftClient.performOpenShiftCommand(request.namespace, command)
        val deployResult = response.success.whenTrue {
            redeployer.performDeploymentRequest(request.namespace, request.name)
        }

        return listOf(response).addIfNotNull(deployResult)
    }
}

data class RenewRequest(
    val name: String,
    val namespace: String,
    val affiliation: String,
    val commonName: String,
    val ownerReference: OwnerReference,
    val suffix: String = "cert"
)
