package no.skatteetaten.aurora.boober.service.resourceprovisioning

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.newObjectMeta
import io.fabric8.kubernetes.api.model.HasMetadata
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.feature.*
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.openshift.StorageGridObjectArea
import no.skatteetaten.aurora.boober.model.openshift.StorageGridObjectAreaSpec
import no.skatteetaten.aurora.boober.model.openshift.fqn
import no.skatteetaten.aurora.boober.service.*
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftDeployer
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3StorageGridProvisioner.SgoaStatus.Status.Reason.Undefined
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3StorageGridProvisioner.SgoaStatus.Status.Result
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
) {
    companion object {
        fun fromJsonNodes(jsonNode: JsonNode): StorageGridCredentials {
            /*
            {
                "tenantName" : "aurora-utv",
                "bucketName" : "aurora-utv-paas-bucket-u-default",
                "objectPrefix" : "9d63b79a-95b7-4e00-9105-041200e6895c",
                "username" : "utv-aurora-bas-test-refapps3",
                "password" : "S3userpass",
                "serviceEndpoint" : "http://uia0ins-netapp-storagegrid01.skead.no:10880/",
                "accessKey" : "MJYKL3BRODDGNQNN35LW",
                "secretKey" : "Kh/XX1zSVaEHNLfp+hzaFUVcdiyXZjlQcx59U5L7"
            }
             */
            return jacksonObjectMapper().convertValue(jsonNode)
        }
    }
}

class S3ProvisioningException(message: String, cause: Throwable? = null) : ProvisioningException(message, cause)

private val logger = KotlinLogging.logger { }

@Service
class S3StorageGridProvisioner(
    val openShiftDeployer: OpenShiftDeployer,
    val openShiftClient: OpenShiftClient,
    val openShiftCommandService: OpenShiftCommandService,
    val herkimerService: HerkimerService,
    @Value("\${minio.bucket.region:us-east-1}") val defaultBucketRegion: String,
    @Value("\${storagegrid.provisioning-timeout:20000}") val provisioningTimeout: Long,
    @Value("\${storagegrid.provisioning-status-check-interval:1000}") val statusCheckIntervalMillis: Long
) {
    private val mapper = jacksonObjectMapper()
        .apply { enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE) }

    fun getOrProvisionCredentials(adc: AuroraDeploymentSpec): List<ObjectAreaWithCredentials> {

        provisionMissingObjectAreas(adc)
        return herkimerService.getObjectAreaAdminCredentials(adc)
            .also { logger.debug("Found ${it.size} ObjectArea credentials for ApplicationDeployment ${adc.namespace}/${adc.name}") }
    }

    fun provisionMissingObjectAreas(adc: AuroraDeploymentSpec) {
        val existingResources = herkimerService.getObjectAreaResourcesIndex(adc)
        val missingObjectAreas = adc.s3ObjectAreas.filter { !existingResources.containsKey(it.specifiedAreaKey) }
        provisionObjectAreas(adc, missingObjectAreas)
    }

    private fun provisionObjectAreas(adc: AuroraDeploymentSpec, objectAreas: List<S3ObjectArea>) {
        val sgoas = objectAreas
            .map { objectArea -> createSgoaResource(adc, objectArea) }
            .onEach { sgoa ->
                val response = openShiftDeployer.applyResource(sgoa.metadata.namespace, sgoa)
                response.exception?.let { throw S3ProvisioningException("Unable to apply SGOA ${sgoa.fqn}. $it") }
            }

        val requests = sgoas.map { SgoaWithStatus(it) }.toMutableList()
        val startTime = System.currentTimeMillis()
        fun hasTimedOut() = System.currentTimeMillis() - startTime > provisioningTimeout
        while (true) {
            requests.forEach { sgoaWithStatus ->
                val sgoa = sgoaWithStatus.sgoa
                val response = openShiftGetObject(sgoaWithStatus.sgoa)
                sgoaWithStatus.status = response.responseBody?.let { mapper.convertValue<SgoaStatus>(it) }
                    ?: throw S3ProvisioningException("Unexpectedly got empty body when requesting status for SGOA ${sgoa.fqn}")
                logger.debug("Status for SGOA ${sgoa.fqn} is ${sgoaWithStatus.status.status.result}")
            }
            if (requests.all { it.status.status.result.reason.endState } || hasTimedOut()) break

            Thread.sleep(statusCheckIntervalMillis)
        }

        val errors = requests.filter { !it.status.status.result.success }

        val failedRequests = errors.filter { it.status.status.result.reason.endState }.takeIf { it.isNotEmpty() }
            ?.also { logger.debug("ApplicationDeployment ${adc.name} failed ${it.size} SGOA request(s)") }
        failedRequests?.let { throw S3ProvisioningException("${it.size} StorageGridObjectArea request(s) failed. Check status with \"oc get sgoa -o yaml\"") }

        val timedOutRequests = errors.filter { !it.status.status.result.reason.endState }.takeIf { it.isNotEmpty() }
            ?.also { logger.debug("ApplicationDeployment ${adc.name} timed out ${it.size} SGOA request(s)") }
        timedOutRequests?.let { throw S3ProvisioningException("Timed out waiting for ${it.size} StorageGridObjectArea request(s) to complete. Check status with \"oc get sgoa -o yaml\"") }
    }

    data class SgoaWithStatus(
        val sgoa: StorageGridObjectArea,
        var status: SgoaStatus = SgoaStatus()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SgoaStatus(val status: Status = Status(result = Result("", Undefined, false))) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Status(val result: Result) {
            enum class Reason(val endState: Boolean = false) {
                @JsonEnumDefaultValue
                Undefined,
                SGOAProvisioned(true),
                AdIdNotBelongToNamespace(true),
                ApplicationDeploymentDoesNotExist(true),
                TenantAccountDoesNotExist(true),
                FailedToProvisionObjectArea,
                SpecConsistencyValidationFailure(true),
                FailureFromHerkimer,
                InternalError(true)
            }

            data class Result(val message: String, val reason: Reason, val success: Boolean)
        }
    }

    private fun openShiftGetObject(resource: HasMetadata): OpenShiftResponse {
        val namespace = resource.metadata.namespace
        val o = openShiftCommandService.createOpenShiftCommand(namespace, resource)
            .copy(operationType = OperationType.GET)
        return openShiftClient.performOpenShiftCommand(namespace, o)
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
            return StorageGridCredentials.fromJsonNodes(adminClaim.credentials)
                .copy(bucketRegion = defaultBucketRegion)
        }
}

private fun createSgoaResource(adc: AuroraDeploymentSpec, objectArea: S3ObjectArea): StorageGridObjectArea {
    val objectAreaName = "${adc.name}-${objectArea.specifiedAreaKey}"
    return StorageGridObjectArea(
        _metadata = newObjectMeta {
            name = objectAreaName
            namespace = adc.namespace
            labels = mapOf("id" to adc.applicationDeploymentId).normalizeLabels()
        },
        spec = StorageGridObjectAreaSpec(
            objectArea.bucketName,
            adc.applicationDeploymentId,
            objectArea.specifiedAreaKey
        )
    )
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