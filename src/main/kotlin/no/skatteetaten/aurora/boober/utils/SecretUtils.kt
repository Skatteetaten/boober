package no.skatteetaten.aurora.boober.utils

import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.kubernetes.secretKeyRef
import com.fkorotkov.kubernetes.valueFrom
import io.fabric8.kubernetes.api.model.Secret

fun Secret.createEnvVarRefs(
    properties: List<String> = this.data.map { it.key },
    prefix: String = "",
    forceUpperCaseForEnvVarName: Boolean = true
) =
    properties.map { propertyName ->
        val envVarName = "$prefix$propertyName"
        val secretName = this.metadata.name
        newEnvVar {
            name = envVarName
            valueFrom {
                secretKeyRef {
                    key = if (forceUpperCaseForEnvVarName) propertyName.toUpperCase() else propertyName
                    name = secretName
                    optional = false
                }
            }
        }
    }
