package no.skatteetaten.aurora.boober.utils

import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.kubernetes.secretKeyRef
import com.fkorotkov.kubernetes.valueFrom
import io.fabric8.kubernetes.api.model.Secret

fun Secret.createEnvVarRefs(
    properties: Set<String> = this.data.keys,
    prefix: String = "",
    propertyNameAsUpperCase: Boolean = true
) =
    properties.map { propertyName ->
        val formattedPropertyName = if (propertyNameAsUpperCase) propertyName.toUpperCase() else propertyName
        val envVarName = "${prefix.toUpperCase()}$formattedPropertyName"
        val secretName = this.metadata.name
        newEnvVar {
            name = envVarName
            valueFrom {
                secretKeyRef {
                    key = propertyName
                    name = secretName
                    optional = false
                }
            }
        }
    }
