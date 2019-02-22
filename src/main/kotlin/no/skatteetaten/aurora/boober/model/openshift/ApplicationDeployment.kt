package no.skatteetaten.aurora.boober.model.openshift

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.ObjectMeta
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.service.AuroraConfigRef

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApplicationDeployment(
    val spec: ApplicationDeploymentSpec,
    private var metadata: ObjectMeta,
    private val kind: String = "ApplicationDeployment",
    private var apiVersion: String = "skatteetaten.no/v1"
) : HasMetadata {

    override fun getMetadata() = metadata
    override fun getKind() = kind
    override fun getApiVersion() = apiVersion
    override fun setMetadata(metadata: ObjectMeta?) {
        metadata?.let { this.metadata = it }
    }

    override fun setApiVersion(version: String?) {
        version?.let { this.apiVersion = it }
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
