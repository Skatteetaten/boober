package no.skatteetaten.aurora.boober.service.internal

import com.fkorotkov.kubernetes.configMap
import com.fkorotkov.kubernetes.metadata
import io.fabric8.kubernetes.api.model.ConfigMap

object ConfigMapGenerator {

    fun create(cmName: String, cmLabels: Map<String, String>, cmData: Map<String, String>): ConfigMap {

        return configMap {

            apiVersion = "v1"

            metadata {
                labels = cmLabels
                name = cmName

                finalizers = null
                ownerReferences = null
            }
            data = cmData
        }

    }
}