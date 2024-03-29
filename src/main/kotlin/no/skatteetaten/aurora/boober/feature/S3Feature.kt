package no.skatteetaten.aurora.boober.feature

import java.util.UUID
import org.apache.commons.codec.binary.Base64
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newSecret
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.Secret
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.addEnvVarsToMainContainers
import no.skatteetaten.aurora.boober.model.findSubKeys
import no.skatteetaten.aurora.boober.service.HerkimerService
import no.skatteetaten.aurora.boober.service.ResourceHerkimer
import no.skatteetaten.aurora.boober.service.ResourceKind
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3Access
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3Provisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3ProvisioningRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3ProvisioningResult
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.createEnvVarRefs
import no.skatteetaten.aurora.boober.utils.findResourcesByType
import no.skatteetaten.aurora.boober.utils.allowedPattern
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean

private const val BUCKET_OBJECT_AREA_CONTEXT_KEY = "bucketObjectAreas"

private val FeatureContext.bucketObjectArea: List<S3BucketObjectArea>
    get() = this.getContextKey(
        BUCKET_OBJECT_AREA_CONTEXT_KEY
    )

@ConditionalOnProperty(value = ["integrations.s3.variant"], havingValue = "none", matchIfMissing = true)
@Service
class S3DisabledFeature : S3FeatureTemplate() {

    private val logger = KotlinLogging.logger { }

    init {
        logger.info("S3 support is disabled")
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {
        val isS3Enabled = adc.s3ObjectAreas.isNotEmpty()

        return if (isS3Enabled) {
            listOf(IllegalArgumentException("S3 storage is not available in this cluster=${adc.cluster}"))
        } else {
            emptyList()
        }
    }
}

@ConditionalOnBean(S3Provisioner::class)
@Service
class S3Feature(
    val s3Provisioner: S3Provisioner,
    val herkimerService: HerkimerService,
    @Value("\${application.deployment.id}") val booberApplicationdeploymentId: String,
    @Value("\${openshift.cluster}") val cluster: String,
    @Value("\${minio.bucket.region:us-east-1}") val defaultBucketRegion: String
) : S3FeatureTemplate() {

    private val logger = KotlinLogging.logger { }

    init {
        logger.info("Enabling Minio S3 Feature")
    }

    override fun createContext(
        spec: AuroraDeploymentSpec,
        cmd: AuroraContextCommand,
        validationContext: Boolean
    ): Map<String, Any> {
        val s3BucketObjectAreas = findS3Buckets(spec)
        return mapOf(
            BUCKET_OBJECT_AREA_CONTEXT_KEY to s3BucketObjectAreas
        )
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {

        val s3BucketObjectAreas = context.bucketObjectArea

        val requiredFieldsExceptions = s3BucketObjectAreas.validateRequiredFieldsArePresent()
        val duplicateObjectAreaInSameBucketExceptions = s3BucketObjectAreas.verifyObjectAreaAndBucketAreUnique()
        val notCurrentAffilationException =
            s3BucketObjectAreas.validateTenantContainsCurrentAffiliationPrefix(adc.affiliation)

        val validationConfigExceptions =
            requiredFieldsExceptions + duplicateObjectAreaInSameBucketExceptions + notCurrentAffilationException
        if (validationConfigExceptions.isNotEmpty()) {
            return validationConfigExceptions
        }

        if (!fullValidation || adc.cluster != cluster || s3BucketObjectAreas.isEmpty()) return emptyList()

        return s3BucketObjectAreas.verifyBucketCredentialsExistOrElseException()
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        val s3BucketObjectAreas = context.bucketObjectArea

        if (s3BucketObjectAreas.isEmpty()) return emptySet()

        val s3Credentials = s3BucketObjectAreas.getOrProvisionCredentials(adc)

        val s3Secret = s3Credentials.createS3Secrets(adc.namespace, adc.name)

        return s3Secret.map { it.generateAuroraResource() }.toSet()
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, context: FeatureContext) {
        val envVars = resources.extractS3EnvVarsFromSecrets()

        resources.addEnvVarsToMainContainers(envVars, javaClass)
    }

    private fun List<S3BucketObjectArea>.verifyBucketCredentialsExistOrElseException(): List<IllegalArgumentException> {
        val nameAndCredentials = getBucketCredentials()

        return this.mapNotNull {
            val credentials = nameAndCredentials[it.bucketName.trim()]

            if (credentials == null) IllegalArgumentException("Could not find credentials for bucket with name=${it.bucketName}, please register the credentials")
            else null
        }
    }

    private fun Set<AuroraResource>.extractS3EnvVarsFromSecrets(): List<EnvVar> =
        findResourcesByType<Secret>("-s3")
            .map { secret ->
                val objectArea = secret.metadata.annotations[ANNOTATION_OBJECT_AREA]

                secret.createEnvVarRefs(prefix = "S3_BUCKETS_${objectArea}_")
            }.flatten()

    private fun getBucketCredentials(): Map<String, JsonNode?> =
        herkimerService.getClaimedResources(
            claimOwnerId = booberApplicationdeploymentId,
            resourceKind = ResourceKind.MinioPolicy
        ).associate {
            it.name to it.claims.singleOrNull()?.credentials
        }

    private fun provisionAndStoreS3Credentials(
        s3BucketObjectArea: S3BucketObjectArea,
        adc: AuroraDeploymentSpec,
        bucketAdmins: Map<String, ResourceHerkimer>,
        objectAreaResourceName: String
    ): S3Credentials {
        val request = S3ProvisioningRequest(
            bucketName = s3BucketObjectArea.bucketName,
            path = UUID.randomUUID().toString().replace("-", ""),
            userName = UUID.randomUUID().toString().replace("-", ""),
            access = listOf(S3Access.WRITE, S3Access.DELETE, S3Access.READ)
        )

        val s3Credentials =
            s3Provisioner.provision(request)
                .toS3Credentials(s3BucketObjectArea.bucketName, request.path, s3BucketObjectArea.area)

        val bucketAdmin = bucketAdmins[s3BucketObjectArea.bucketName]
            ?: throw IllegalArgumentException("Could not find bucket credentials for bucket. This should not happen. It has been validated in validate step")

        herkimerService.createResourceAndClaim(
            ownerId = adc.applicationDeploymentId,
            resourceKind = ResourceKind.MinioObjectArea,
            resourceName = objectAreaResourceName,
            credentials = s3Credentials,
            parentId = bucketAdmin.id,
            claimName = "ADMIN"
        )

        return s3Credentials
    }

    private fun List<S3BucketObjectArea>.getOrProvisionCredentials(adc: AuroraDeploymentSpec): List<BucketWithCredentials> {
        val resourceWithClaims =
            herkimerService.getClaimedResources(adc.applicationDeploymentId, ResourceKind.MinioObjectArea)
                .associateBy { it.name }

        val s3BucketAdmins =
            herkimerService.getClaimedResources(booberApplicationdeploymentId, ResourceKind.MinioPolicy)
                .associateBy { it.name }

        return this.map { s3BucketObjectArea ->
            val objectAreaResourceName = "${s3BucketObjectArea.bucketName}/${s3BucketObjectArea.area}"
            val credentialsStoredInHerkimer = resourceWithClaims[objectAreaResourceName]
                ?.claims
                ?.map { it.credentials }
                ?.let { S3Credentials.fromJsonNodes(it) }
                ?.find { it.objectArea == s3BucketObjectArea.area }

            val s3Credentials = credentialsStoredInHerkimer
                ?: provisionAndStoreS3Credentials(
                    s3BucketObjectArea = s3BucketObjectArea,
                    adc = adc,
                    bucketAdmins = s3BucketAdmins,
                    objectAreaResourceName = objectAreaResourceName
                )

            BucketWithCredentials(
                s3BucketObjectArea,
                s3Credentials
            )
        }
    }

    private fun S3ProvisioningResult.toS3Credentials(bucketName: String, objectPrefix: String, objectArea: String) =
        S3Credentials(
            objectArea = objectArea,
            serviceEndpoint = serviceEndpoint,
            accessKey = accessKey,
            secretKey = secretKey,
            bucketName = bucketName,
            objectPrefix = objectPrefix,
            bucketRegion = defaultBucketRegion
        )

    private fun findS3Buckets(adc: AuroraDeploymentSpec): List<S3BucketObjectArea> {
        return if (adc.isSimplifiedAndEnabled(FEATURE_FIELD_NAME)) {
            val defaultS3Bucket = S3BucketObjectArea(
                bucketName = adc["$FEATURE_DEFAULTS_FIELD_NAME/bucketName"],
                area = adc["$FEATURE_DEFAULTS_FIELD_NAME/objectArea"],
                tenant = adc["$FEATURE_DEFAULTS_FIELD_NAME/tenant"]
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
    ): S3BucketObjectArea? {
        if (!adc.get<Boolean>("$FEATURE_FIELD_NAME/$s3ObjectAreaKey/enabled")) return null

        return S3BucketObjectArea(
            bucketName = adc.getOrNull("$FEATURE_FIELD_NAME/$s3ObjectAreaKey/bucketName")
                ?: adc["$FEATURE_DEFAULTS_FIELD_NAME/bucketName"],
            area = adc["$FEATURE_FIELD_NAME/$s3ObjectAreaKey/objectArea"],
            tenant = adc.getOrNull("$FEATURE_FIELD_NAME/$s3ObjectAreaKey/tenant")
                ?: adc["$FEATURE_DEFAULTS_FIELD_NAME/tenant"]
        )
    }

    private fun List<S3BucketObjectArea>.validateRequiredFieldsArePresent(): List<IllegalArgumentException> {
        return this.flatMap {
            val bucketNameException =
                if (it.bucketName.isEmpty()) IllegalArgumentException("Missing field: bucketName for s3") else null
            val objectAreaException =
                if (it.area.isEmpty()) IllegalArgumentException("Missing field: objectArea for s3") else null

            listOf(
                bucketNameException,
                objectAreaException
            )
        }.filterNotNull()
    }
}

private fun List<S3BucketObjectArea>.verifyObjectAreaAndBucketAreUnique(): List<IllegalArgumentException> {
    val s3BucketObjectAreaGroupedByArea = this.groupBy { it }
    return s3BucketObjectAreaGroupedByArea.filter { it.value.size > 1 }
        .map { (s3BucketObjectArea, duplicatedEntries) ->
            IllegalArgumentException("Duplicated objectArea=${s3BucketObjectArea.area} in same bucket=${s3BucketObjectArea.bucketName}")
        }
}

private fun List<S3BucketObjectArea>.validateTenantContainsCurrentAffiliationPrefix(affiliation: String): List<IllegalArgumentException> {
    return this.filterNot {
        it.tenant.startsWith(affiliation)
    }.map {
        IllegalArgumentException("tenant must contain current affiliation=$affiliation as a prefix, specified value was: ${it.tenant}")
    }
}

private data class BucketWithCredentials(
    val s3BucketObjectArea: S3BucketObjectArea,
    val s3Credentials: S3Credentials
)

private const val FEATURE_FIELD_NAME = "s3"
private const val FEATURE_DEFAULTS_FIELD_NAME = "s3Defaults"
private const val ANNOTATION_OBJECT_AREA = "minio.skatteetaten.no/objectArea"

private fun List<BucketWithCredentials>.createS3Secrets(nsName: String, appName: String) =
    this.map { (s3BucketObjectArea, provisionResult) ->
        newSecret {
            metadata {
                name = "$appName-${s3BucketObjectArea.area}-s3"
                namespace = nsName
                annotations = mapOf(
                    ANNOTATION_OBJECT_AREA to s3BucketObjectArea.area
                )
            }
            data = provisionResult.run {
                mapOf(
                    "serviceEndpoint" to serviceEndpoint,
                    "accessKey" to accessKey,
                    "secretKey" to secretKey,
                    "bucketRegion" to bucketRegion,
                    "bucketName" to bucketName,
                    "objectPrefix" to objectPrefix
                ).mapValues { Base64.encodeBase64String(it.value.toByteArray()) }
            }
        }
    }

abstract class S3FeatureTemplate : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {

        val s3Handlers = findS3Handlers(cmd.applicationFiles)
        val s3DefaultHandlers = findS3DefaultHandlers(header)
        return setOf(
            AuroraConfigFieldHandler(
                FEATURE_FIELD_NAME,
                validator = { it.boolean() },
                defaultValue = false,
                canBeSimplifiedConfig = true
            )
        ) + s3Handlers + s3DefaultHandlers
    }

    private fun findS3Handlers(
        applicationFiles: List<AuroraConfigFile>
    ): List<AuroraConfigFieldHandler> =
        applicationFiles.findSubKeys(FEATURE_FIELD_NAME)
            .flatMap { s3BucketObjectArea ->
                if (s3BucketObjectArea.isEmpty()) emptyList()
                else listOf(
                    AuroraConfigFieldHandler("$FEATURE_FIELD_NAME/$s3BucketObjectArea/bucketName"),
                    AuroraConfigFieldHandler(
                        "$FEATURE_FIELD_NAME/$s3BucketObjectArea/objectArea",
                        defaultValue = s3BucketObjectArea
                    ),
                    AuroraConfigFieldHandler(
                        "$FEATURE_FIELD_NAME/$s3BucketObjectArea/enabled",
                        validator = { it.boolean() },
                        defaultValue = true
                    ),
                    AuroraConfigFieldHandler(
                        "$FEATURE_FIELD_NAME/$s3BucketObjectArea/tenant",
                        { tenantPatternValidation(it) }
                    )
                )
            }

    private fun findS3DefaultHandlers(header: AuroraDeploymentSpec): List<AuroraConfigFieldHandler> =
        listOf(
            AuroraConfigFieldHandler("$FEATURE_DEFAULTS_FIELD_NAME/bucketName", defaultValue = ""),
            AuroraConfigFieldHandler(
                "$FEATURE_DEFAULTS_FIELD_NAME/objectArea",
                { objectAreaPatternValidation(it, allowEmpty = true) },
                defaultValue = ""
            ),
            AuroraConfigFieldHandler(
                "$FEATURE_DEFAULTS_FIELD_NAME/tenant",
                { tenantPatternValidation(it) },
                defaultValue = "${header.affiliation}-${header.cluster}"
            )
        )

    private fun objectAreaPatternValidation(it: JsonNode?, allowEmpty: Boolean = false): Exception? {
        val allowEmptyRegex = if (allowEmpty) "^$|" else ""
        return it.allowedPattern(
            pattern = "$allowEmptyRegex[a-z0-9-.]+",
            message = "s3 objectArea can only contain lower case characters, numbers, hyphen(-) or period(.), specified value was: ${it?.toPrettyString()}",
            required = false
        )
    }

    private fun tenantPatternValidation(it: JsonNode?): Exception? {
        return it?.allowedPattern(
            pattern = "([a-z]+)-([a-zA-Z0-9-]+)",
            message = "s3 tenant must be on the form affiliation-cluster, specified value was: ${it.toPrettyString()}"
        )
    }
}

data class S3BucketObjectArea(
    val bucketName: String,
    val area: String,
    val tenant: String
)

data class S3Credentials(
    val objectArea: String,
    val serviceEndpoint: String,
    val accessKey: String,
    val secretKey: String,
    val bucketName: String,
    val objectPrefix: String,
    val bucketRegion: String
) {
    companion object {
        fun fromJsonNodes(jsonNode: List<JsonNode>): List<S3Credentials> = jacksonObjectMapper().convertValue(jsonNode)
    }
}
