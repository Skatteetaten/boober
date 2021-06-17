package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newSecret
import io.fabric8.kubernetes.api.model.Secret
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.addEnvVarsToMainContainers
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.resourceprovisioning.*
import no.skatteetaten.aurora.boober.utils.ConditionalOnPropertyMissingOrEmpty
import no.skatteetaten.aurora.boober.utils.createEnvVarRefs
import no.skatteetaten.aurora.boober.utils.findResourcesByType
import org.apache.commons.codec.binary.Base64
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service


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

    override fun validate(spec: AuroraDeploymentSpec, fullValidation: Boolean, context: FeatureContext)
            : List<Exception> = spec.validateS3()

    override fun generate(spec: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {

        if (spec.s3ObjectAreas.isEmpty()) return emptySet()

        val s3Credentials = s3StorageGridProvisioner.getOrProvisionCredentials(spec)
        val s3Secret = s3Credentials.map { it.createS3Secret(spec.namespace, spec.name) }
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

private fun ObjectAreaWithCredentials.createS3Secret(nsName: String, appName: String) = newSecret {
    metadata {
        name = "$appName-${objectArea.specifiedAreaKey}$S3_RESOURCE_NAME_SUFFIX"
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

private const val S3_RESOURCE_NAME_SUFFIX = "-s3"
private const val ANNOTATION_OBJECT_AREA = "storagegrid.skatteetaten.no/objectArea"
