package no.skatteetaten.aurora.boober.service.resourceprovisioning

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.newObjectMeta
import no.skatteetaten.aurora.boober.feature.*
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.openshift.StorageGridObjectArea
import no.skatteetaten.aurora.boober.model.openshift.StorageGridObjectAreaSpec
import no.skatteetaten.aurora.boober.service.*
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftDeployer
import no.skatteetaten.aurora.boober.service.openshift.OperationType
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
    fun getOrProvisionCredentials(adc: AuroraDeploymentSpec): List<ObjectAreaWithCredentials> {

        provisionMissingObjectAreas(adc)
        val objectAreaAdminCredentials = herkimerService.getObjectAreaAdminCredentials(adc)
        return objectAreaAdminCredentials
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
                val response = openShiftDeployer.applyResource(sgoa._metadata!!.namespace, sgoa)
                response.exception?.let { throw S3ProvisioningException("Unable to apply SGOA resource ${sgoa._metadata?.name}. $it") }
            }

        val pendingRequests = sgoas.toMutableList()
        val startTime = System.currentTimeMillis()
        fun timeSpent() = System.currentTimeMillis() - startTime
        while (pendingRequests.isNotEmpty() && timeSpent() < provisioningTimeout) {
            pendingRequests.removeAll {
                val status = it.status
                println(status)
                status != null
            }
            Thread.sleep(statusCheckIntervalMillis)
        }
    }


    private val StorageGridObjectArea.status
        get(): String? {
            val namespace = this._metadata!!.namespace
            val o = openShiftCommandService.createOpenShiftCommand(namespace, this)
                .copy(operationType = OperationType.GET)
            val checkResponse = openShiftClient.performOpenShiftCommand(namespace, o)
            val responseBody = checkResponse.responseBody!!
            println(responseBody.toPrettyString())
            return try {
                responseBody["status"]["result"]["reason"].asText()
            } catch (e: Exception) {
                null
            }
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