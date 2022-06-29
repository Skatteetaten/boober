package no.skatteetaten.aurora.boober.utils

import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.kubernetes.secretKeyRef
import com.fkorotkov.kubernetes.valueFrom
import io.fabric8.kubernetes.api.model.Secret
import java.nio.charset.Charset
import java.util.Base64

fun Secret.createEnvVarRefs(
    properties: Set<String> = this.data.keys,
    prefix: String = "",
    uppercaseSuffix: Boolean = true
) =
    properties.map { propertyName ->
        val formattedPropertyName = if (uppercaseSuffix) propertyName.uppercase() else propertyName
        val envVarName = "${prefix.uppercase()}$formattedPropertyName"
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

fun MutableMap<String, String>.editEncodedValue(
    key: String,
    editFunction: (String) -> String
) = apply {
    this[key] = this[key]
        .let(Base64.getDecoder()::decode)
        ?.toString(Charset.defaultCharset())
        ?.let(editFunction)
        ?.toByteArray()
        .let(Base64.getEncoder()::encodeToString)
}
