package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.newOwnerReference
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
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
    val commonName: String,
    val owner: Owner
)

data class Owner(
    val apiVersion: String,
    val kind: String,
    val name: String,
    val uid: String
)

@Service
class StsRenewService(
    val provsioner: StsProvisioner,
    val commandService: OpenShiftCommandService,
    val openshiftClient: OpenShiftClient,
    val redeployer: RedeployService,
    val userDetailsProvider: UserDetailsProvider
) {

    fun renew(request: RenewRequest): List<OpenShiftResponse> {

        val stsResult = provsioner.generateCertificate(
            request.commonName,
            request.name,
            request.namespace
        )

        val ownerReference = OwnerReferenceBuilder()
            .withApiVersion(request.owner.apiVersion)
            .withKind(request.owner.kind)
            .withName(request.owner.name)
            .withUid(request.owner.uid)
            .build()

        val labels = mapOf(
            "app" to request.name,
            "updatedBy" to userDetailsProvider.getAuthenticatedUser().username.replace(":", "-"),
            "affiliation" to request.affiliation
        )

        val secret = StsSecretGenerator.create(request.name, stsResult, labels, ownerReference)
        val json = jacksonObjectMapper().convertValue<JsonNode>(secret)
        val command = commandService.createOpenShiftCommand(request.namespace, json)

        val response = openshiftClient.performOpenShiftCommand(request.namespace, command)
        val deployResult = response.success.whenTrue {
            redeployer.performDeploymentRequest(request.namespace, request.name)
        }

        return listOf(response).addIfNotNull(deployResult)
    }
}