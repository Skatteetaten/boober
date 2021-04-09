package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newObjectMeta
import com.fkorotkov.kubernetes.newSecret
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.Secret
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.addEnvVarsToMainContainers
import no.skatteetaten.aurora.boober.model.openshift.StorageGridObjectArea
import no.skatteetaten.aurora.boober.model.openshift.StorageGridObjectAreaSpec
import no.skatteetaten.aurora.boober.service.HerkimerService
import no.skatteetaten.aurora.boober.service.OpenShiftCommandService
import no.skatteetaten.aurora.boober.service.ResourceKind
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

private const val OBJECT_AREA_CONTEXT_KEY = "objectAreas"

private val FeatureContext.bucketObjectArea: List<S3ObjectArea>
    get() = this.getContextKey(OBJECT_AREA_CONTEXT_KEY)

@ConditionalOnProperty(value = ["integrations.herkimer.url"])
@ConditionalOnPropertyMissingOrEmpty("integrations.fiona.url")
@Service
class S3StorageGridFeature(
    val openShiftDeployer: OpenShiftDeployer,
    val openShiftClient: OpenShiftClient,
    val openShiftCommandService: OpenShiftCommandService,
    val herkimerService: HerkimerService,
    @Value("\${application.deployment.id}") val booberApplicationdeploymentId: String,
    @Value("\${openshift.cluster}") val cluster: String,
    @Value("\${minio.bucket.region:us-east-1}") val defaultBucketRegion: String
) : S3FeatureTemplate() {

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return !header.isJob
    }

    override fun createContext(
        spec: AuroraDeploymentSpec,
        cmd: AuroraContextCommand,
        validationContext: Boolean
    ): Map<String, Any> {
        val s3BucketObjectAreas = findS3ObjectAreas(spec)
        return mapOf(OBJECT_AREA_CONTEXT_KEY to s3BucketObjectAreas)
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {

        return emptyList()
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        val s3BucketObjectAreas = context.bucketObjectArea

        if (s3BucketObjectAreas.isEmpty()) return emptySet()

        val s3Credentials = getOrProvisionCredentials(s3BucketObjectAreas, adc)

        val s3Secret = s3Credentials.createS3Secrets(adc.namespace, adc.name)

        return s3Secret.map { it.generateAuroraResource() }.toSet()
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, context: FeatureContext) {
        val envVars = resources.extractS3EnvVarsFromSecrets()

        resources.addEnvVarsToMainContainers(envVars, javaClass)
    }

    private fun getOrProvisionCredentials(
        objectAreas: List<S3ObjectArea>,
        adc: AuroraDeploymentSpec
    ): List<ObjectAreaWithCredentials> {
        val resourceWithClaims =
            herkimerService.getClaimedResources(adc.applicationDeploymentId, ResourceKind.StorageGridObjectArea)
                .associateBy { it.name }

        return objectAreas.map { objectArea ->

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
            var status: String? = null
            while (status == null) {
                val o = openShiftCommandService.createOpenShiftCommand(adc.namespace, resource)
                    .copy(operationType = OperationType.GET)
                val checkResponse = openShiftClient.performOpenShiftCommand(adc.namespace, o)
                val responseBody = checkResponse.responseBody!!
                status = try {
                    responseBody["status"]["conditions"].asIterable().first()["type"].asText()
                } catch (e: java.lang.Exception) {
                    null
                }
                Thread.sleep(1000)
            }

            val objectAreaResourceName = objectArea.specifiedAreaKey
            val credentialsStoredInHerkimer = resourceWithClaims[objectAreaResourceName]
                ?.claims
                ?.map { it.credentials }
                ?.let { StorageGridCredentials.fromJsonNodes(it) }
                ?.first() // TODO: Find based on objectArea name

            val s3Credentials = credentialsStoredInHerkimer ?: throw Exception("No credentials found")

            ObjectAreaWithCredentials(
                objectArea,
                s3Credentials
            )
        }
    }


    private fun Set<AuroraResource>.extractS3EnvVarsFromSecrets(): List<EnvVar> =
        findResourcesByType<Secret>("-s3")
            .map { secret ->
                val objectArea = secret.metadata.annotations[ANNOTATION_OBJECT_AREA]

                secret.createEnvVarRefs(prefix = "S3_BUCKETS_${objectArea}_")
            }.flatten()

    private fun findS3ObjectAreas(adc: AuroraDeploymentSpec): List<S3ObjectArea> {
        return if (adc.isSimplifiedAndEnabled(FEATURE_FIELD_NAME)) {
            val defaultS3Bucket = S3ObjectArea(
                tenant = "${adc.affiliation}-${adc.cluster}",
                bucketName = adc["$FEATURE_DEFAULTS_FIELD_NAME/bucketName"],
                area = adc["$FEATURE_DEFAULTS_FIELD_NAME/objectArea"]
            )

            listOf(defaultS3Bucket)
        } else {
            adc.getSubKeyValues(FEATURE_FIELD_NAME)
                .mapNotNull { findS3Bucket(it, adc) }
        }
    }

    private fun findS3Bucket(
        s3ObjectAreaKey: String,
        adc: AuroraDeploymentSpec
    ): S3ObjectArea? {
        if (!adc.get<Boolean>("$FEATURE_FIELD_NAME/$s3ObjectAreaKey/enabled")) return null

        return S3ObjectArea(
            tenant = "${adc.affiliation}-${adc.cluster}",
            bucketName = adc.getOrNull("$FEATURE_FIELD_NAME/$s3ObjectAreaKey/bucketName")
                ?: adc["$FEATURE_DEFAULTS_FIELD_NAME/bucketName"],
            area = adc["$FEATURE_FIELD_NAME/$s3ObjectAreaKey/objectArea"],
            specifiedAreaKey = s3ObjectAreaKey
        )
    }

}

private data class ObjectAreaWithCredentials(
    val objectArea: S3ObjectArea,
    val s3Credentials: StorageGridCredentials
)

private data class S3ObjectArea(
    val tenant: String,
    val bucketName: String,
    val area: String,
    val specifiedAreaKey: String = area
)

private data class StorageGridCredentials(
    val host: String,
    val username: String,
    val password: String,
    val s3accesskey: String,
    val s3secretaccesskey: String
) {
    companion object {
        fun fromJsonNodes(jsonNode: List<JsonNode>): List<StorageGridCredentials> =
            jacksonObjectMapper().convertValue(jsonNode)
    }
}

private fun List<ObjectAreaWithCredentials>.createS3Secrets(nsName: String, appName: String) =
    this.map { (s3BucketObjectArea, provisionResult) ->
        newSecret {
            metadata {
                name = "$appName-${s3BucketObjectArea.specifiedAreaKey}-s3"
                namespace = nsName
                annotations = mapOf(
                    ANNOTATION_OBJECT_AREA to s3BucketObjectArea.specifiedAreaKey
                )
            }
            data = provisionResult.run {
                mapOf(
                    "serviceEndpoint" to host,
                    "accessKey" to s3accesskey,
                    "secretKey" to s3secretaccesskey,
                    "bucketRegion" to "us-east", // TODO: Fix these values
                    "bucketName" to "dummy-for-now",
                    "objectPrefix" to "dummy"
                ).mapValues { Base64.encodeBase64String(it.value.toByteArray()) }
            }
        }
    }



private const val FEATURE_FIELD_NAME = "s3"
private const val FEATURE_DEFAULTS_FIELD_NAME = "s3Defaults"
private const val ANNOTATION_OBJECT_AREA = "storagegrid.skatteetaten.no/objectArea"