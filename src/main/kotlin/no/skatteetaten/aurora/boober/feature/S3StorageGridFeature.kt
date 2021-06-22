package no.skatteetaten.aurora.boober.feature

import org.apache.commons.codec.binary.Base64
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Service
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newSecret
import io.fabric8.kubernetes.api.model.Secret
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.addEnvVarsToMainContainers
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3StorageGridProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SgProvisioningRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SgRequestsWithCredentials
import no.skatteetaten.aurora.boober.utils.createEnvVarRefs
import no.skatteetaten.aurora.boober.utils.findResourcesByType

@ConditionalOnBean(S3StorageGridProvisioner::class)
@Service
class S3StorageGridFeature(
    val s3StorageGridProvisioner: S3StorageGridProvisioner,
    val openShiftClient: OpenShiftClient,
) : S3FeatureTemplate() {

    private val logger = KotlinLogging.logger { }

    init {
        logger.info("Enabling StorageGrid S3 Feature")
    }

    override fun enable(header: AuroraDeploymentSpec) = !header.isJob

    override fun createContext(spec: AuroraDeploymentSpec, cmd: AuroraContextCommand, validationContext: Boolean)
        : Map<String, Any> = emptyMap()

    override fun validate(spec: AuroraDeploymentSpec, fullValidation: Boolean, context: FeatureContext)
        : List<Exception> = spec.validateS3()

    override fun generate(spec: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {

        val s3ObjectAreas = spec.s3ObjectAreas
        if (s3ObjectAreas.isEmpty()) return emptySet()

        val requests = s3ObjectAreas
            .map { SgProvisioningRequest(it.tenant, it.area, spec.name, spec.namespace, it.bucketName) }
        val s3Credentials = s3StorageGridProvisioner.getOrProvisionCredentials(spec.applicationDeploymentId, requests)
        logger.debug("Found ${s3Credentials.size} ObjectArea credentials for ApplicationDeployment ${spec.namespace}/${spec.name}")
        val s3Secret = s3Credentials.map(SgRequestsWithCredentials::toS3Secret)
        return s3Secret.map { it.generateAuroraResource() }.toSet()
    }

    override fun modify(spec: AuroraDeploymentSpec, resources: Set<AuroraResource>, context: FeatureContext) {
        val secrets = resources.s3Secrets
        val envVars = createEnvVarRefs(secrets)
        resources.addEnvVarsToMainContainers(envVars, javaClass)
    }

    private fun createEnvVarRefs(secrets: List<Secret>) = secrets.map { secret ->
        val objectArea = secret.metadata.annotations[ANNOTATION_OBJECT_AREA]
        secret.createEnvVarRefs(prefix = "S3_BUCKETS_${objectArea}_")
    }.flatten()

    private val Set<AuroraResource>.s3Secrets: List<Secret> get() = findResourcesByType(S3_RESOURCE_NAME_SUFFIX)
}

private fun SgRequestsWithCredentials.toS3Secret() = newSecret {
    metadata {
        name = "${request.deploymentName}-${request.objectAreaName}$S3_RESOURCE_NAME_SUFFIX"
        namespace = request.targetNamespace
        annotations = mapOf(
            ANNOTATION_OBJECT_AREA to request.objectAreaName
        )
    }
    data = credentials.run {
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

private const val S3_RESOURCE_NAME_SUFFIX = "-s3"
private const val ANNOTATION_OBJECT_AREA = "storagegrid.skatteetaten.no/objectArea"