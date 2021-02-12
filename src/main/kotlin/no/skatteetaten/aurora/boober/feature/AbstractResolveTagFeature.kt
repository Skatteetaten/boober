package no.skatteetaten.aurora.boober.feature

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.service.ImageMetadata

private const val IMAGE_METADATA_CONTEXT_KEY = "imageMetadata"

abstract class AbstractResolveTagFeature(open val cantusService: CantusService) : Feature {
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
        val imageInformationResult = cantusService.getImageMetadata(repo, name, tag)

        return mapOf(
            IMAGE_METADATA_CONTEXT_KEY to imageInformationResult
        )
    }
}
