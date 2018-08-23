package no.skatteetaten.aurora.boober.model.openshift

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import io.fabric8.kubernetes.api.model.ObjectMeta
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.service.AuroraConfigRef

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AuroraApplicationInstance(
    val kind: String = "AuroraApplicationInstance",
    val metadata: ObjectMeta,
    val apiVersion: String = "skatteetaten.no/v1",
    val spec: ApplicationSpec
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApplicationSpec(
    val applicationId: String,
    val applicationInstanceId: String,
    val splunkIndex: String? = null,
    val managementPath: String?,
    val releaseTo: String?,
    val deployTag: String?,
    val selector: Map<String, String>,
    val command: ApplicationCommand
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApplicationCommand(
    val overrideFiles: Map<String, String>,
    val applicationDeploymentRef: ApplicationDeploymentRef,
    val auroraConfig: AuroraConfigRef
)

data class AuroraConfigRef(
    val name: String,
    val refName: String
)
