package no.skatteetaten.aurora.boober.feature

import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.service.ImageMetadata

private const val IMAGE_METADATA_CONTEXT_KEY = "imageMetadata"

private val logger = KotlinLogging.logger {}

abstract class AbstractResolveTagFeature(
    open val cantusService: CantusService,
    cluster: String
) : DatabaseFeatureTemplate(cluster) {
    /* TODO: Måtte legge inn DatabaseFeatureTemplate her for å gi ToxiproxyFeature tilgang til handlers,
        men det burde vel være mulig å gjøre det på en litt ryddigere måte? */

    internal val FeatureContext.imageMetadata: ImageMetadata
        get() = this.getContextKey(
            IMAGE_METADATA_CONTEXT_KEY
        )

    abstract fun isActive(spec: AuroraDeploymentSpec): Boolean

    fun dockerDigestExistsWarning(context: FeatureContext): String? {
        val imageMetadata = context.imageMetadata

        return if (imageMetadata.dockerDigest == null) {
            "Was unable to resolve dockerDigest for image=${imageMetadata.getFullImagePath()}. Using tag instead."
        } else {
            null
        }
    }

    fun createImageMetadataContext(repo: String, name: String, tag: String): FeatureContext {
        logger.debug("Asking cantus about /$repo/$name/$tag")
        val imageInformationResult = cantusService.getImageMetadata(repo, name, tag)

        logger.debug("Cantus says: ${imageInformationResult.imagePath} / ${imageInformationResult.imageTag} / ${imageInformationResult.getFullImagePath()}")
        return mapOf(
            IMAGE_METADATA_CONTEXT_KEY to imageInformationResult
        )
    }
}
