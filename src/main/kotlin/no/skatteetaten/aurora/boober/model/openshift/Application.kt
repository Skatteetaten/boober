package no.skatteetaten.aurora.boober.model.openshift

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import io.fabric8.kubernetes.api.model.ObjectMeta
import no.skatteetaten.aurora.boober.mapper.AuroraConfigField

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Application(
    val kind: String = "Application",
    val metadata: ObjectMeta,
    val apiVersion: String = "skatteetaten.no/v1",
    val spec: ApplicationSpec
)

data class ApplicationSpec(val fields: Map<String, AuroraConfigField>)



