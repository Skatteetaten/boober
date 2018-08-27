package no.skatteetaten.aurora.boober.service.internal

import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newConfigMap
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.OwnerReference

object ConfigMapGenerator {

    fun create(
        cmName: String,
        cmLabels: Map<String, String>,
        cmData: Map<String, String>,
        ownerReference: OwnerReference
    ): ConfigMap {

        return newConfigMap {
            apiVersion = "v1"
            metadata {
                ownerReferences = listOf(ownerReference)
                labels = cmLabels
                name = cmName
            }
            data = cmData
        }
    }
}