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
    val specifiedAreaKey: String,
    val area: String = specifiedAreaKey,
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
    @Value("\${storagegrid.bucket.region:us-east-1}") val defaultBucketRegion: String,
    @Value("\${storagegrid.provisioning-timeout:20000}") val provisioningTimeout: Long,
    @Value("\${storagegrid.provisioning-status-check-interval:1000}") val statusCheckIntervalMillis: Long
) {
    fun getOrProvisionCredentials(spec: AuroraDeploymentSpec): List<ObjectAreaWithCredentials> {

        if (spec.s3ObjectAreas.isEmpty()) return emptyList()

        provisionMissingObjectAreas(spec)
        return herkimerService.getObjectAreaAdminCredentials(spec)
            .also { logger.debug("Found ${it.size} ObjectArea credentials for ApplicationDeployment ${spec.namespace}/${spec.name}") }
    }

    fun provisionMissingObjectAreas(spec: AuroraDeploymentSpec) {
        val existingResources = herkimerService.getObjectAreaResourcesIndex(spec)
        if (existingResources.isNotEmpty()) {
            logger.debug("ObjectArea(s) ${existingResources.keys.joinToString()} was already provisioned for ADC ${spec.name}")
        }
        val missingObjectAreas = spec.s3ObjectAreas
            .filter { !existingResources.containsKey(it.area) }
        provisionObjectAreas(spec, missingObjectAreas)
    }

    private fun provisionObjectAreas(spec: AuroraDeploymentSpec, objectAreas: List<S3ObjectArea> = spec.s3ObjectAreas) {

        if (objectAreas.isEmpty()) return
        logger.debug("Provisioning ObjectArea(s) ${objectAreas.joinToString { it.area }} for spec ${spec.name}")

        val sgoas = applySgoaResources(spec, objectAreas)
        val requests = sgoas.waitForRequestCompletionOrTimeout()
        handleErrors(spec, requests)
    }

    private fun applySgoaResources(spec: AuroraDeploymentSpec, objectAreas: List<S3ObjectArea>) = objectAreas
        .map { objectArea -> createSgoaResource(spec, objectArea) }
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

    private fun handleErrors(spec: AuroraDeploymentSpec, requests: List<SgoaAndResult>) {
        val errors = requests.filter { !it.result.success }

        errors.filter { it.result.reason.endState }.takeIf { it.isNotEmpty() }
            ?.also { logger.debug("ApplicationDeployment ${spec.name} failed ${it.size} SGOA request(s)") }
            ?.let { throw S3ProvisioningException("${it.size} StorageGridObjectArea request(s) failed. Check status with \"oc get sgoa -o yaml\"") }

        errors.filter { !it.result.reason.endState }.takeIf { it.isNotEmpty() }
            ?.also { logger.debug("ApplicationDeployment ${spec.name} timed out ${it.size} SGOA request(s)") }
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

    private fun HerkimerService.getObjectAreaAdminCredentials(spec: AuroraDeploymentSpec): List<ObjectAreaWithCredentials> {

        val sgoaResources = getObjectAreaResourcesIndex(spec)
        return spec.s3ObjectAreas.map { objectArea ->

            val objectAreaResourceName = objectArea.area
            val sgoaResource = sgoaResources[objectAreaResourceName]
                ?: throw S3ProvisioningException("Unable to find resource of kind ${ResourceKind.StorageGridObjectArea} with name $objectAreaResourceName for AuroraDeploymentSpec ${spec.name}")

            ObjectAreaWithCredentials(objectArea, sgoaResource.adminClaimCredentials)
        }
    }

    private fun HerkimerService.getObjectAreaResourcesIndex(spec: AuroraDeploymentSpec): Map<String, ResourceHerkimer> =
        getClaimedResources(spec.applicationDeploymentId, ResourceKind.StorageGridObjectArea)
            .associateBy { it.name }

    private val ResourceHerkimer.adminClaimCredentials
        get(): StorageGridCredentials {
            val claimName = "ADMIN"
            val adminClaim = claims.find { it.name == claimName }
                ?: throw S3ProvisioningException("Unable to find claim with name $claimName for resource of kind ${this.kind} with name ${this.name}")
            return adminClaim.credentials.convert<StorageGridCredentials>()
                .copy(bucketRegion = defaultBucketRegion)
        }

    private fun createSgoaResource(spec: AuroraDeploymentSpec, objectArea: S3ObjectArea): StorageGridObjectArea {
        val objectAreaName = "${spec.name}-${objectArea.area}"
        // TODO: We need to somehow get the ownerReference set
        val labels = operationScopeFeature.getLabelsToAdd()
            .normalizeLabels()
        return StorageGridObjectArea(
            _metadata = newObjectMeta {
                name = objectAreaName
                namespace = spec.namespace
                this.labels = labels
            },
            spec = StorageGridObjectAreaSpec(
                objectArea.bucketName,
                spec.applicationDeploymentId,
                objectArea.area
            )
        )
    }
}

val AuroraDeploymentSpec.s3ObjectAreas
    get(): List<S3ObjectArea> {

        val tenantName = "$affiliation-$cluster"
        val defaultBucketName: String = this["$FEATURE_DEFAULTS_FIELD_NAME/bucketName"]
        val defaultObjectAreaName =
            this.get<String>("$FEATURE_DEFAULTS_FIELD_NAME/objectArea").takeIf { it.isNotBlank() } ?: "default"

        return if (this.isSimplifiedAndEnabled(FEATURE_FIELD_NAME)) {
            val defaultS3Bucket = S3ObjectArea(
                tenant = tenantName,
                bucketName = defaultBucketName,
                specifiedAreaKey = defaultObjectAreaName
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
                        specifiedAreaKey = objectAreaName,
                        area = this["$FEATURE_FIELD_NAME/$objectAreaName/objectArea"]
                    )
                }
        }
    }

fun AuroraDeploymentSpec.validateS3(): List<IllegalArgumentException> {

    val objectAreas = this.s3ObjectAreas
    if (objectAreas.isEmpty()) return emptyList()

    val requiredFieldsExceptions = objectAreas.validateRequiredFieldsArePresent()
    val duplicateObjectAreaInSameBucketExceptions = objectAreas.verifyObjectAreasAreUnique()

    return requiredFieldsExceptions + duplicateObjectAreaInSameBucketExceptions
}

private fun List<S3ObjectArea>.validateRequiredFieldsArePresent(): List<IllegalArgumentException> {
    return this.flatMap {
        val bucketNameException =
            if (it.bucketName.isEmpty()) IllegalArgumentException("Missing field: bucketName for s3") else null
        val objectAreaException =
            if (it.area.isEmpty()) IllegalArgumentException("Missing field: objectArea for s3") else null

        listOf(bucketNameException, objectAreaException)
    }.filterNotNull()
}

private fun List<S3ObjectArea>.verifyObjectAreasAreUnique(): List<IllegalArgumentException> {
    return groupBy { it.area }
        .mapValues { it.value.size }
        .filter { it.value > 1 }
        .map { (name, count) -> IllegalArgumentException("objectArea name=${name} used $count times for same application") }
}

private const val FEATURE_FIELD_NAME = "s3"
private const val FEATURE_DEFAULTS_FIELD_NAME = "s3Defaults"