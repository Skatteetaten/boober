package no.skatteetaten.aurora.boober.service.internal

import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newConfigMap
import io.fabric8.kubernetes.api.model.ConfigMap

object ConfigMapGenerator {

    fun create(cmName: String, cmLabels: Map<String, String>, cmData: Map<String, String>): ConfigMap {

        return newConfigMap {
            apiVersion = "v1"
            metadata {
                labels = cmLabels
                name = cmName
            }
            data = cmData
        }
    }
}