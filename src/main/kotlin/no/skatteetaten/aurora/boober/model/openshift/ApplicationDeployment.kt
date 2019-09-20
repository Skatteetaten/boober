package no.skatteetaten.aurora.boober.model.openshift

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceColumnDefinition
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.service.AuroraConfigRef

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApplicationDeployment(
        val spec: ApplicationDeploymentSpec,
        var _metadata: ObjectMeta?,
        val _kind: String = "ApplicationDeployment",
        var _apiVersion: String = "skatteetaten.no/v1"
) : HasMetadata { //or just KubernetesResource?
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
        val applicationId: String,
        val applicationDeploymentId: String,
        val applicationName: String,
        val applicationDeploymentName: String,
        val databases: List<String>,
        val splunkIndex: String? = null,
        val managementPath: String?,
        val releaseTo: String?,
        val deployTag: String?,
        val selector: Map<String, String>,
        val command: ApplicationDeploymentCommand,
        val message: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApplicationDeploymentCommand(
        val overrideFiles: Map<String, String>,
        val applicationDeploymentRef: ApplicationDeploymentRef,
        val auroraConfig: AuroraConfigRef
)
