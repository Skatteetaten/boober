package no.skatteetaten.aurora.boober.service.resourceprovisioning

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fkorotkov.kubernetes.newObjectMeta
import io.fabric8.kubernetes.api.model.HasMetadata
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.feature.OperationScopeFeature
import no.skatteetaten.aurora.boober.model.openshift.*
import no.skatteetaten.aurora.boober.service.*
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftDeployer
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.utils.convert
import no.skatteetaten.aurora.boober.utils.normalizeLabels
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

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
    val openShiftCommandService: OpenShiftCommandService,
    val herkimerService: HerkimerService,
    val operationScopeFeature: OperationScopeFeature,
    @Value("\${integrations.storagegrid.bucket.region:us-east-1}") val defaultBucketRegion: String,
    @Value("\${integrations.storagegrid.provisioning-timeout:20000}") val provisioningTimeout: Long,
    @Value("\${integrations.storagegrid.provisioning-status-check-interval:1000}") val statusCheckIntervalMillis: Long
) {
    private val logger = KotlinLogging.logger { }

    fun getOrProvisionCredentials(applicationDeploymentId: String, requests: List<SgProvisioningRequest>)
            : List<SgRequestsWithCredentials> {

        if (requests.isEmpty()) return emptyList()

        provisionMissingObjectAreas(applicationDeploymentId, requests)
        return herkimerService.getObjectAreaAdminCredentials(applicationDeploymentId, requests)
    }

    fun provisionMissingObjectAreas(applicationDeploymentId: String, requests: List<SgProvisioningRequest>) {
        val existingResources = herkimerService.getObjectAreaResourcesIndex(applicationDeploymentId)
        if (existingResources.isNotEmpty()) {
            logger.debug("ObjectArea(s) ${existingResources.keys.joinToString()} was already provisioned for applicationDeploymentId=$applicationDeploymentId")
        }
        val missingObjectAreas = requests
            .filter { !existingResources.containsKey(it.objectAreaName) }
        provisionObjectAreas(applicationDeploymentId, missingObjectAreas)
    }

    private fun provisionObjectAreas(applicationDeploymentId: String, requests: List<SgProvisioningRequest>) {

        if (requests.isEmpty()) return
        logger.debug("Provisioning ObjectArea(s) ${requests.joinToString { it.objectAreaName }} for applicationDeploymentId=$applicationDeploymentId")

        val sgoas = applySgoaResources(applicationDeploymentId, requests)
        val results = sgoas.waitForRequestCompletionOrTimeout()
        handleErrors(applicationDeploymentId, results)
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
                val response = openShiftGetObject(sgoaAndResult.sgoa)
                sgoaAndResult.result = response.responseBody?.get("status")
                    ?.convert<StorageGridObjectAreaStatus>()?.result
                    ?: StorageGridObjectAreaStatusResult()
                logger.debug("Status for SGOA ${sgoaAndResult.sgoa.fqn} is ${sgoaAndResult.result}")
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

    private fun openShiftGetObject(resource: HasMetadata): OpenShiftResponse {
        val namespace = resource.metadata.namespace
        val cmd = openShiftCommandService.createOpenShiftCommand(namespace, resource)
            .copy(operationType = OperationType.GET)
        return openShiftClient.performOpenShiftCommand(cmd)
    }

    private fun HerkimerService.getObjectAreaAdminCredentials(
        applicationDeploymentId: String,
        requests: List<SgProvisioningRequest>
    ): List<SgRequestsWithCredentials> {

        val sgoaResources = getObjectAreaResourcesIndex(applicationDeploymentId)
        return requests.map { request ->

            val objectAreaResourceName = request.objectAreaName
            val sgoaResource = sgoaResources[objectAreaResourceName]
                ?: throw S3ProvisioningException("Unable to find resource of kind ${ResourceKind.StorageGridObjectArea} with name $objectAreaResourceName for applicationDeploymentId=${applicationDeploymentId}")

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

    private fun createSgoaResource(applicationDeploymentId: String, request: SgProvisioningRequest)
            : StorageGridObjectArea {
        val objectAreaName = "${request.deploymentName}-${request.objectAreaName}"
        // TODO: We need to somehow get the ownerReference set
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