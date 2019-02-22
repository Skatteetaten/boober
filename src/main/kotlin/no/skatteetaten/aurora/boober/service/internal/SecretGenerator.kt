package no.skatteetaten.aurora.boober.service.internal

import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newSecret
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.Secret
import no.skatteetaten.aurora.boober.utils.whenTrue
import org.apache.commons.codec.binary.Base64

object SecretGenerator {

    fun create(
        secretName: String,
        secretLabels: Map<String, String>,
        secretData: Map<String, ByteArray>?,
        secretAnnotations: Map<String, String> = emptyMap(),
        ownerReference: OwnerReference,
        secretNamespace: String
    ): Secret {

        return newSecret {
            metadata {
                labels = secretLabels
                name = secretName
                namespace = secretNamespace
                ownerReferences = listOf(ownerReference)
                secretAnnotations.isNotEmpty().whenTrue {
                    annotations = secretAnnotations
                }
            }
            secretData?.let {
                data = it.mapValues { Base64.encodeBase64String(it.value) }
            }
        }
    }
}