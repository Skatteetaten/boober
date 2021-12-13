package no.skatteetaten.aurora.boober.service.resourceprovisioning

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.newObjectMeta
import io.fabric8.kubernetes.api.model.HasMetadata
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.feature.OperationScopeFeature
import no.skatteetaten.aurora.boober.model.openshift.StorageGridObjectArea
import no.skatteetaten.aurora.boober.model.openshift.StorageGridObjectAreaSpec
import no.skatteetaten.aurora.boober.model.openshift.StorageGridObjectAreaStatus
import no.skatteetaten.aurora.boober.model.openshift.StorageGridObjectAreaStatusResult
import no.skatteetaten.aurora.boober.model.openshift.fqn
import no.skatteetaten.aurora.boober.model.openshift.reason
import no.skatteetaten.aurora.boober.service.HerkimerService
import no.skatteetaten.aurora.boober.service.ProvisioningException
import no.skatteetaten.aurora.boober.service.ResourceHerkimer
import no.skatteetaten.aurora.boober.service.ResourceKind
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftDeployer
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType.GET
import no.skatteetaten.aurora.boober.utils.appropriateNamedUrl
import no.skatteetaten.aurora.boober.utils.convert
import no.skatteetaten.aurora.boober.utils.normalizeLabels

data class SgProvisioningRequest(
    val tenant: String,
    val objectAreaName: String,
    val deploymentName: String,
    val targetNamespace: String,
    val bucketPostfix: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StorageGridCredentials(
    val tenantName: String,
    val serviceEndpoint: String,
    val accessKey: String,
    val secretKey: String,
    val bucketName: String,
    val objectPrefix: String,
    val username: String,
    val password: String,
    val bucketRegion: String? = null
)

data class SgRequestsWithCredentials(
    val request: SgProvisioningRequest,
    val credentials: StorageGridCredentials
)

class S3ProvisioningException(message: String, cause: Throwable? = null) : ProvisioningException(message, cause)

@Service
@ConditionalOnProperty(value = ["integrations.s3.variant"], havingValue = "storagegrid", matchIfMissing = false)
class S3StorageGridProvisioner(
    val openShiftDeployer: OpenShiftDeployer,
    val openShiftClient: OpenShiftClient,
    val herkimerService: HerkimerService,
    val operationScopeFeature: OperationScopeFeature,
    @Value("\${integrations.storagegrid.bucket.region:us-east-1}") val defaultBucketRegion: String,
    @Value("\${integrations.storagegrid.provisioning-timeout:20000}") val provisioningTimeout: Long,
    @Value("\${integrations.storagegrid.provisioning-status-check-interval:1000}") val statusCheckIntervalMillis: Long
) {
    private val logger = KotlinLogging.logger { }

    fun getOrProvisionCredentials(applicationDeploymentId: String, requests: List<SgProvisioningRequest>):
        SgoaWithCredentials {

        if (requests.isEmpty()) return SgoaWithCredentials(emptyList(), emptyList())

        val sgoas = provisionMissingObjectAreas(applicationDeploymentId, requests)

        val credentials = herkimerService.getObjectAreaAdminCredentials(applicationDeploymentId, requests)

        return SgoaWithCredentials(sgoas, credentials)
    }

    fun provisionMissingObjectAreas(applicationDeploymentId: String, requests: List<SgProvisioningRequest>): List<StorageGridObjectArea> {
        if (requests.isEmpty()) return emptyList()
        val existingResources = herkimerService.getObjectAreaResourcesIndex(applicationDeploymentId)
        if (existingResources.isNotEmpty()) {
            logger.debug("ObjectArea(s) ${existingResources.keys.joinToString()} was already provisioned for applicationDeploymentId=$applicationDeploymentId")
        }
        val missingObjectAreas = requests
            .filter { !existingResources.containsKey(it.objectAreaName) }
        return provisionObjectAreas(applicationDeploymentId, missingObjectAreas)
    }

    private fun provisionObjectAreas(applicationDeploymentId: String, requests: List<SgProvisioningRequest>): List<StorageGridObjectArea> {

        if (requests.isEmpty()) return emptyList()
        logger.debug("Provisioning ObjectArea(s) ${requests.joinToString { it.objectAreaName }} for applicationDeploymentId=$applicationDeploymentId")

        val sgoas = applySgoaResources(applicationDeploymentId, requests)
        val results = sgoas.waitForRequestCompletionOrTimeout()
        handleErrors(applicationDeploymentId, results)
        return sgoas
    }

    private fun applySgoaResources(applicationDeploymentId: String, requests: List<SgProvisioningRequest>) = requests
        .map { request -> createSgoaResource(applicationDeploymentId, request) }
        .onEach { sgoa ->
            val response = openShiftDeployer.applyResource(sgoa.metadata.namespace, sgoa)
            response.exception?.let<String, Unit> { throw S3ProvisioningException("Unable to apply SGOA ${sgoa.fqn}. $it") }
        }

    private fun List<StorageGridObjectArea>.waitForRequestCompletionOrTimeout(): List<SgoaAndResult> {
        val requests = this.map { SgoaAndResult(it) }.toMutableList()
        val startTime = System.currentTimeMillis()
        fun hasTimedOut() = System.currentTimeMillis() - startTime > provisioningTimeout
        while (true) {
            requests.forEach { sgoaAndResult ->
                val sgoa = sgoaAndResult.sgoa
                val response = openShiftClient.performOpenShiftCommand(sgoa.toGetCommand())
                sgoaAndResult.result = response.responseBody?.get("status")
                    ?.convert<StorageGridObjectAreaStatus>()?.result
                    ?: StorageGridObjectAreaStatusResult()
                logger.debug("Status for SGOA ${sgoa.fqn} is ${sgoaAndResult.result}")
            }
            if (requests.all { it.result.reason.endState } || hasTimedOut()) break

            Thread.sleep(statusCheckIntervalMillis)
        }
        return requests
    }

    private fun handleErrors(applicationDeploymentId: String, results: List<SgoaAndResult>) {
        val errors = results.filter { !it.result.success }

        errors.filter { it.result.reason.endState }.takeIf { it.isNotEmpty() }
            ?.also { logger.debug("ApplicationDeployment $applicationDeploymentId failed ${it.size} SGOA request(s)") }
            ?.let { throw S3ProvisioningException("${it.size} StorageGridObjectArea request(s) failed. Check status with \"oc get sgoa -o yaml\"") }

        errors.filter { !it.result.reason.endState }.takeIf { it.isNotEmpty() }
            ?.also { logger.debug("ApplicationDeployment $applicationDeploymentId timed out ${it.size} SGOA request(s)") }
            ?.let { throw S3ProvisioningException("Timed out waiting for ${it.size} StorageGridObjectArea request(s) to complete. Check status with \"oc get sgoa -o yaml\"") }
    }

    private data class SgoaAndResult(
        val sgoa: StorageGridObjectArea,
        var result: StorageGridObjectAreaStatusResult = StorageGridObjectAreaStatusResult()
    )

    private fun HerkimerService.getObjectAreaAdminCredentials(
        applicationDeploymentId: String,
        requests: List<SgProvisioningRequest>
    ): List<SgRequestsWithCredentials> {

        val sgoaResources = getObjectAreaResourcesIndex(applicationDeploymentId)
        return requests.map { request ->

            val objectAreaResourceName = request.objectAreaName
            val sgoaResource = sgoaResources[objectAreaResourceName]
                ?: throw S3ProvisioningException("Unable to find resource of kind ${ResourceKind.StorageGridObjectArea} with name $objectAreaResourceName for applicationDeploymentId=$applicationDeploymentId")

            SgRequestsWithCredentials(request, sgoaResource.adminClaimCredentials)
        }
    }

    private fun HerkimerService.getObjectAreaResourcesIndex(applicationDeploymentId: String): Map<String, ResourceHerkimer> =
        getClaimedResources(applicationDeploymentId, ResourceKind.StorageGridObjectArea)
            .associateBy { it.name }

    private val ResourceHerkimer.adminClaimCredentials
        get(): StorageGridCredentials {
            val claimName = "ADMIN"
            val adminClaim = claims.find { it.name == claimName }
                ?: throw S3ProvisioningException("Unable to find claim with name $claimName for resource of kind ${this.kind} with name ${this.name}")
            return adminClaim.credentials.convert<StorageGridCredentials>()
                .copy(bucketRegion = defaultBucketRegion)
        }

    private fun createSgoaResource(applicationDeploymentId: String, request: SgProvisioningRequest):
        StorageGridObjectArea {
        val objectAreaName = "${request.deploymentName}-${request.objectAreaName}"
        val labels = operationScopeFeature.getLabelsToAdd()
            .normalizeLabels()
        return StorageGridObjectArea(
            _metadata = newObjectMeta {
                name = objectAreaName
                namespace = request.targetNamespace
                this.labels = labels
            },
            spec = StorageGridObjectAreaSpec(
                request.bucketPostfix,
                applicationDeploymentId,
                request.objectAreaName
            )
        )
    }
}

private fun HasMetadata.toGetCommand() = OpenshiftCommand(GET, this.toJson().appropriateNamedUrl, this.toJson())

private fun HasMetadata.toJson() = jacksonObjectMapper().convertValue<JsonNode>(this)
