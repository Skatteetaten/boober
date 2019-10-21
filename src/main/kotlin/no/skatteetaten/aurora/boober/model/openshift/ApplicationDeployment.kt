package no.skatteetaten.aurora.boober.model.openshift

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.ObjectMeta
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.service.AuroraConfigRef

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder(value = ["apiVersion", "kind", "metadata", "spec"])
data class ApplicationDeployment(
    val spec: ApplicationDeploymentSpec,
    @JsonIgnore
    var _metadata: ObjectMeta?,
    @JsonIgnore
    val _kind: String = "ApplicationDeployment",
    @JsonIgnore
    var _apiVersion: String = "skatteetaten.no/v1"
) : HasMetadata { // or just KubernetesResource?
    override fun getMetadata(): ObjectMeta {
        return _metadata ?: ObjectMeta()
    }

    override fun getKind(): String {
        return _kind
    }

    override fun getApiVersion(): String {
        return _apiVersion
    }

    override fun setMetadata(metadata: ObjectMeta?) {
        _metadata = metadata
    }

    override fun setApiVersion(version: String?) {
        _apiVersion = apiVersion
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApplicationDeploymentSpec(
    var applicationId: String? = null,
    var selector: Map<String, String> = emptyMap(),
    var applicationDeploymentId: String = "",
    var applicationName: String? = null,
    var applicationDeploymentName: String = "",
    var databases: List<String> = emptyList(),
    var splunkIndex: String? = null,
    var managementPath: String? = null,
    var releaseTo: String? = null,
    var deployTag: String? = null,
    var command: ApplicationDeploymentCommand? = null,
    var message: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApplicationDeploymentCommand(
    val overrideFiles: Map<String, String>,
    val applicationDeploymentRef: ApplicationDeploymentRef,
    val auroraConfig: AuroraConfigRef
)
