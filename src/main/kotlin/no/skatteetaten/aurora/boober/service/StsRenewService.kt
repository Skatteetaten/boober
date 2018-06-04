package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.model.AuroraCertificateSpec
import no.skatteetaten.aurora.boober.service.internal.StsSecretGenerator
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioner
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.whenTrue
import org.springframework.stereotype.Service

data class RenewRequest(
    val name: String,
    val namespace: String,
    val affiliation: String,
    val ttl: String,
    val renewBefore: String,
    val commonName: String
)
@Service
class StsRenewService(
    val provsioner: StsProvisioner,
    val commandBuilder: OpenShiftCommandBuilder,
    val openshiftClient: OpenShiftClient,
    val redeployer: RedeployService,
    val userDetailsProvider: UserDetailsProvider
) {

    fun renew(request: RenewRequest): List<OpenShiftResponse> {

        val stsResult = provsioner.generateCertificate(
            AuroraCertificateSpec(
                commonName = request.commonName,
                ttl = request.ttl,
                renewBefore = request.renewBefore
            )
        )

        val labels = mapOf(
            "app" to request.name,
            "updatedBy" to userDetailsProvider.getAuthenticatedUser().username.replace(":", "-"),
            "affiliation" to request.affiliation
        )

        val secret = StsSecretGenerator.create(request.name, stsResult, labels)
        val json = jacksonObjectMapper().convertValue<JsonNode>(secret)
        val command = commandBuilder.createOpenShiftCommand(request.namespace, json)

        val response = openshiftClient.performOpenShiftCommand(request.namespace, command)
        val deployResult = response.success.whenTrue {
            redeployer.performDeploymentRequest(request.namespace, request.name)
        }

        return listOf(response).addIfNotNull(deployResult)
    }
}