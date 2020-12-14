package no.skatteetaten.aurora.boober.utils

import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.kubernetes.secretKeyRef
import com.fkorotkov.kubernetes.valueFrom
import io.fabric8.kubernetes.api.model.Secret

fun Secret.createEnvVarRefs(
    properties: Set<String> = this.data.keys,
    prefix: String = "",
    forceUpperCaseForEnvVarName: Boolean = true
) =
    properties.map { propertyName ->
        val envVarName = "$prefix$propertyName".toUpperCase()
        val secretName = this.metadata.name
        newEnvVar {
            name = envVarName
            valueFrom {
                secretKeyRef {
                    //TODO: could not get this to work.
           //         key = if (forceUpperCaseForEnvVarName) propertyName.toUpperCase() else propertyName
                    key = propertyName
                    name = secretName
                    optional = false
                }
            }
        }
    }
