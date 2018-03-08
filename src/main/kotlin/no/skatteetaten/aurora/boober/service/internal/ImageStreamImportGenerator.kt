package no.skatteetaten.aurora.boober.service.internal

import no.skatteetaten.aurora.boober.model.openshift.From
import no.skatteetaten.aurora.boober.model.openshift.ImageStreamImport
import no.skatteetaten.aurora.boober.model.openshift.ImagesItem
import no.skatteetaten.aurora.boober.model.openshift.ImportPolicy
import no.skatteetaten.aurora.boober.model.openshift.Metadata
import no.skatteetaten.aurora.boober.model.openshift.Spec
import no.skatteetaten.aurora.boober.model.openshift.To

object ImageStreamImportGenerator {

    fun create(dockerImageUrl: String, imageStreamName: String): ImageStreamImport {
        return ImageStreamImport(
                metadata = Metadata(name = imageStreamName),
                spec = Spec(
                        images = listOf(ImagesItem(
                                from = From(
                                        kind = "DockerImage",
                                        name = dockerImageUrl
                                ),
                                to = To(
                                        name = "default"
                                ),
                                importPolicy = ImportPolicy(
                                        scheduled = true
                                )
                        ))
                )
        )
    }
}