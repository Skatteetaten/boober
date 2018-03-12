package no.skatteetaten.aurora.boober.service.internal

import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.secret
import io.fabric8.kubernetes.api.model.Secret
import org.apache.commons.codec.binary.Base64

object SecretGenerator {

    fun create(secretName: String, secretLabels: Map<String, String>, secretData: Map<String, ByteArray>?): Secret {

        return secret {
            apiVersion = "v1"

            metadata {
                labels = secretLabels
                name = secretName
                finalizers = null
                ownerReferences = null
            }
            secretData?.let {
                data = it.mapValues { Base64.encodeBase64String(it.value) }
            }
        }
    }
}