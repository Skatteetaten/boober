package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newObjectMeta
import com.fkorotkov.kubernetes.newSecret
import io.fabric8.kubernetes.api.model.Secret
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.addEnvVarsToMainContainers
import no.skatteetaten.aurora.boober.model.openshift.StorageGridObjectArea
import no.skatteetaten.aurora.boober.model.openshift.StorageGridObjectAreaSpec
import no.skatteetaten.aurora.boober.service.*
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftDeployer
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.utils.ConditionalOnPropertyMissingOrEmpty
import no.skatteetaten.aurora.boober.utils.createEnvVarRefs
import no.skatteetaten.aurora.boober.utils.findResourcesByType
import no.skatteetaten.aurora.boober.utils.normalizeLabels
import org.apache.commons.codec.binary.Base64
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

class S3ProvisioningException(message: String, cause: Throwable? = null) : ProvisioningException(message, cause)

@Service
class S3StorageGridProvisioner(
    val openShiftDeployer: OpenShiftDeployer,
    val openShiftClient: OpenShiftClient,
    val openShiftCommandService: OpenShiftCommandService,
    val herkimerService: HerkimerService,
    @Value("\${minio.bucket.region:us-east-1}") val defaultBucketRegion: String
) {
    fun getOrProvisionCredentials(adc: AuroraDeploymentSpec): List<ObjectAreaWithCredentials> {

        val objectAreas: List<S3ObjectArea> = adc.s3ObjectAreas
        provisionMissingObjectAreas(adc, objectAreas)
        return getCredentials(adc, objectAreas)
    }

    private fun provisionMissingObjectAreas(adc: AuroraDeploymentSpec, objectAreas: List<S3ObjectArea>) {
        val existingResourcesWithClaims =
            herkimerService.getClaimedResources(adc.applicationDeploymentId, ResourceKind.StorageGridObjectArea)
                .associateBy { it.name }
        val missingObjectAreas = objectAreas.filter { !existingResourcesWithClaims.containsKey(it.specifiedAreaKey) }
        provisionObjectAreas(adc, missingObjectAreas)
    }

    private fun provisionObjectAreas(adc: AuroraDeploymentSpec, missingObjectAreas: List<S3ObjectArea>) {
        missingObjectAreas.map { objectArea ->

            val objectAreaName = "${adc.name}-${objectArea.specifiedAreaKey}"
            val resource = StorageGridObjectArea(
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
            val response = openShiftDeployer.applyResource(adc.namespace, resource)
            response.exception?.let { throw Exception(it) }

            // TODO: This is rubbish. Make better.
            var attempts = 0
            var status: String? = null
            while (status == null && attempts <= 10) {
                val o = openShiftCommandService.createOpenShiftCommand(adc.namespace, resource)
                    .copy(operationType = OperationType.GET)
                val checkResponse = openShiftClient.performOpenShiftCommand(adc.namespace, o)
                val responseBody = checkResponse.responseBody!!
                status = try {
                    responseBody["status"]["conditions"].asIterable().first()["type"].asText()
                } catch (e: Exception) {
                    null
                }
                Thread.sleep(1000)
                attempts++
            }
        }
    }

    private fun getCredentials(adc: AuroraDeploymentSpec, objectAreas: List<S3ObjectArea>)
            : List<ObjectAreaWithCredentials> {

        val sgoaResources =
            herkimerService.getClaimedResources(adc.applicationDeploymentId, ResourceKind.StorageGridObjectArea)
                .associateBy { it.name }

        return objectAreas.map { objectArea ->

            val objectAreaResourceName = objectArea.specifiedAreaKey
            val sgoaResource = sgoaResources.get(objectAreaResourceName)
                ?: throw S3ProvisioningException("Unable to find resource of kind ${ResourceKind.StorageGridObjectArea} with name $objectAreaResourceName for ApplicationDeployment ${adc.name}")

            ObjectAreaWithCredentials(objectArea, sgoaResource.adminClaimCredentials)
        }
    }

    private val ResourceHerkimer.adminClaimCredentials
        get(): StorageGridCredentials {
            val claimName = "ADMIN"
            val adminClaim = claims.find { it.name == claimName }
                ?: throw S3ProvisioningException("Unable to find claim with name $claimName for resource of kind ${this.kind} with name ${this.name}")
            return StorageGridCredentials
                .fromJsonNodes(adminClaim.credentials)
                .copy(bucketRegion = defaultBucketRegion)
        }
}

@ConditionalOnProperty(value = ["integrations.herkimer.url"])
@ConditionalOnPropertyMissingOrEmpty("integrations.fiona.url")
@Service
class S3StorageGridFeature(
    val s3StorageGridProvisioner: S3StorageGridProvisioner,
    val openShiftClient: OpenShiftClient,
) : S3FeatureTemplate() {

    override fun enable(header: AuroraDeploymentSpec) = !header.isJob

    override fun createContext(spec: AuroraDeploymentSpec, cmd: AuroraContextCommand, validationContext: Boolean)
            : Map<String, Any> = emptyMap()

    override fun validate(adc: AuroraDeploymentSpec, fullValidation: Boolean, context: FeatureContext)
            : List<Exception> = emptyList()

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {

        val s3Credentials = s3StorageGridProvisioner.getOrProvisionCredentials(adc)
        val s3Secret = s3Credentials.map { it.createS3Secret(adc.namespace, adc.name) }
        return s3Secret.map { it.generateAuroraResource() }.toSet()
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, context: FeatureContext) {
        val secrets = resources.s3Secrets
        val envVars = createEnvVarRefs(secrets)
        resources.addEnvVarsToMainContainers(envVars, javaClass)
    }

    private fun createEnvVarRefs(secrets: List<Secret>) = secrets.map { secret ->
        val objectArea = secret.metadata.annotations[ANNOTATION_OBJECT_AREA]
        secret.createEnvVarRefs(prefix = "S3_BUCKETS_${objectArea}_")
    }.flatten()

    private val Set<AuroraResource>.s3Secrets get() = findResourcesByType<Secret>("-s3")
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

data class StorageGridCredentials(
    val serviceEndpoint: String,
    val accessKey: String,
    val secretKey: String,
    val bucketName: String,
    val objectPrefix: String,
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

private fun ObjectAreaWithCredentials.createS3Secret(nsName: String, appName: String): Secret {

    return newSecret {
        metadata {
            name = "$appName-${objectArea.specifiedAreaKey}-s3"
            namespace = nsName
            annotations = mapOf(
                ANNOTATION_OBJECT_AREA to objectArea.specifiedAreaKey
            )
        }
        data = s3Credentials.run {
            mapOf(
                "serviceEndpoint" to serviceEndpoint,
                "accessKey" to accessKey,
                "secretKey" to secretKey,
                "bucketRegion" to bucketRegion,
                "bucketName" to bucketName,
                "objectPrefix" to objectPrefix
            ).mapValues { Base64.encodeBase64String(it.value?.toByteArray()) }
        }
    }
}


private const val FEATURE_FIELD_NAME = "s3"
private const val FEATURE_DEFAULTS_FIELD_NAME = "s3Defaults"
private const val ANNOTATION_OBJECT_AREA = "storagegrid.skatteetaten.no/objectArea"