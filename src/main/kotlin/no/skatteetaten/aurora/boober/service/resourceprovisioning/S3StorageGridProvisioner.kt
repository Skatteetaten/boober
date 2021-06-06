package no.skatteetaten.aurora.boober.service.resourceprovisioning

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fkorotkov.kubernetes.newObjectMeta
import io.fabric8.kubernetes.api.model.HasMetadata
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.feature.*
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.openshift.*
import no.skatteetaten.aurora.boober.service.*
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftDeployer
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.utils.convert
import no.skatteetaten.aurora.boober.utils.normalizeLabels
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

data class ObjectAreaWithCredentials(
    val objectArea: S3ObjectArea,
    val s3Credentials: StorageGridCredentials
)

data class S3ObjectArea(
    val tenant: String,
    val bucketName: String,
    val area: String,
    val specifiedAreaKey: String = area
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

class S3ProvisioningException(message: String, cause: Throwable? = null) : ProvisioningException(message, cause)

private val logger = KotlinLogging.logger { }

@Service
class S3StorageGridProvisioner(
    val openShiftDeployer: OpenShiftDeployer,
    val openShiftClient: OpenShiftClient,
    val openShiftCommandService: OpenShiftCommandService,
    val herkimerService: HerkimerService,
    val operationScopeFeature: OperationScopeFeature,
    @Value("\${minio.bucket.region:us-east-1}") val defaultBucketRegion: String,
    @Value("\${storagegrid.provisioning-timeout:20000}") val provisioningTimeout: Long,
    @Value("\${storagegrid.provisioning-status-check-interval:1000}") val statusCheckIntervalMillis: Long
) {
    fun getOrProvisionCredentials(adc: AuroraDeploymentSpec): List<ObjectAreaWithCredentials> {

        provisionMissingObjectAreas(adc)
        return herkimerService.getObjectAreaAdminCredentials(adc)
            .also { logger.debug("Found ${it.size} ObjectArea credentials for ApplicationDeployment ${adc.namespace}/${adc.name}") }
    }

    fun provisionMissingObjectAreas(adc: AuroraDeploymentSpec) {
        val existingResources = herkimerService.getObjectAreaResourcesIndex(adc)
        if (existingResources.isNotEmpty()) {
            logger.debug("ObjectArea(s) ${existingResources.keys.joinToString()} was already provisioned for ADC ${adc.name}")
        }
        val missingObjectAreas = adc.s3ObjectAreas.filter { !existingResources.containsKey(it.specifiedAreaKey) }
        provisionObjectAreas(adc, missingObjectAreas)
    }

    private fun provisionObjectAreas(adc: AuroraDeploymentSpec, objectAreas: List<S3ObjectArea>) {

        if (objectAreas.isEmpty()) return
        logger.debug("Provisioning ObjectArea(s) ${objectAreas.joinToString { it.specifiedAreaKey }} for ADC ${adc.name}")

        val sgoas = applySgoaResources(adc, objectAreas)
        val requests = sgoas.waitForRequestCompletionOrTimeout()
        handleErrors(adc, requests)
    }

    private fun applySgoaResources(adc: AuroraDeploymentSpec, objectAreas: List<S3ObjectArea>) = objectAreas
        .map { objectArea -> createSgoaResource(adc, objectArea) }
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

    private fun handleErrors(adc: AuroraDeploymentSpec, requests: List<SgoaAndResult>) {
        val errors = requests.filter { !it.result.success }

        errors.filter { it.result.reason.endState }.takeIf { it.isNotEmpty() }
            ?.also { logger.debug("ApplicationDeployment ${adc.name} failed ${it.size} SGOA request(s)") }
            ?.let { throw S3ProvisioningException("${it.size} StorageGridObjectArea request(s) failed. Check status with \"oc get sgoa -o yaml\"") }

        errors.filter { !it.result.reason.endState }.takeIf { it.isNotEmpty() }
            ?.also { logger.debug("ApplicationDeployment ${adc.name} timed out ${it.size} SGOA request(s)") }
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
        return openShiftClient.performOpenShiftCommand(namespace, cmd)
    }

    private fun HerkimerService.getObjectAreaAdminCredentials(adc: AuroraDeploymentSpec): List<ObjectAreaWithCredentials> {

        val sgoaResources = getObjectAreaResourcesIndex(adc)
        return adc.s3ObjectAreas.map { objectArea ->

            val objectAreaResourceName = objectArea.specifiedAreaKey
            val sgoaResource = sgoaResources[objectAreaResourceName]
                ?: throw S3ProvisioningException("Unable to find resource of kind ${ResourceKind.StorageGridObjectArea} with name $objectAreaResourceName for AuroraDeploymentSpec ${adc.name}")

            ObjectAreaWithCredentials(objectArea, sgoaResource.adminClaimCredentials)
        }
    }

    private fun HerkimerService.getObjectAreaResourcesIndex(adc: AuroraDeploymentSpec): Map<String, ResourceHerkimer> =
        getClaimedResources(adc.applicationDeploymentId, ResourceKind.StorageGridObjectArea)
            .associateBy { it.name }

    private val ResourceHerkimer.adminClaimCredentials
        get(): StorageGridCredentials {
            val claimName = "ADMIN"
            val adminClaim = claims.find { it.name == claimName }
                ?: throw S3ProvisioningException("Unable to find claim with name $claimName for resource of kind ${this.kind} with name ${this.name}")
            return adminClaim.credentials.convert<StorageGridCredentials>()
                .copy(bucketRegion = defaultBucketRegion)
        }

    private fun createSgoaResource(adc: AuroraDeploymentSpec, objectArea: S3ObjectArea): StorageGridObjectArea {
        val objectAreaName = "${adc.name}-${objectArea.specifiedAreaKey}"
        // TODO: We need to somehow get the ownerReference set
        val labels = operationScopeFeature.getLabelsToAdd()
            .normalizeLabels()
        return StorageGridObjectArea(
            _metadata = newObjectMeta {
                name = objectAreaName
                namespace = adc.namespace
                this.labels = labels
            },
            spec = StorageGridObjectAreaSpec(
                objectArea.bucketName,
                adc.applicationDeploymentId,
                objectArea.specifiedAreaKey
            )
        )
    }
}

private val AuroraDeploymentSpec.s3ObjectAreas
    get(): List<S3ObjectArea> {

        val tenantName = "$affiliation-$cluster"
        val defaultBucketName: String = this["$FEATURE_DEFAULTS_FIELD_NAME/bucketName"]

        return if (this.isSimplifiedAndEnabled(FEATURE_FIELD_NAME)) {
            val defaultS3Bucket = S3ObjectArea(
                tenant = tenantName,
                bucketName = defaultBucketName,
                area = this["$FEATURE_DEFAULTS_FIELD_NAME/objectArea"]
            )
            listOf(defaultS3Bucket)
        } else {
            val objectAreaNames = getSubKeyValues(FEATURE_FIELD_NAME)
            objectAreaNames
                .filter { objectAreaName -> this["$FEATURE_FIELD_NAME/$objectAreaName/enabled"] }
                .map { objectAreaName ->
                    S3ObjectArea(
                        tenant = tenantName,
                        bucketName = getOrNull("$FEATURE_FIELD_NAME/$objectAreaName/bucketName") ?: defaultBucketName,
                        area = this["$FEATURE_FIELD_NAME/$objectAreaName/objectArea"],
                        specifiedAreaKey = objectAreaName
                    )
                }
        }
    }

private const val FEATURE_FIELD_NAME = "s3"
private const val FEATURE_DEFAULTS_FIELD_NAME = "s3Defaults"