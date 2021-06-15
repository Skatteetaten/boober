package no.skatteetaten.aurora.boober.model.openshift

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.ObjectMeta

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder(value = ["apiVersion", "kind", "metadata", "spec"])
data class AuroraCname(
    val spec: CnameSpec,
    @JsonIgnore
    var _metadata: ObjectMeta?,
    @JsonIgnore
    val _kind: String = "AuroraCname",
    @JsonIgnore
    var _apiVersion: String = "skatteetaten.no/v1"
) : HasMetadata {
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
data class CnameSpec(
    val cname: String,
    val type: CNameType,
    val host: String,
    val ttl: Int
)

enum class CNameType {
    MSDNS,
    AzureDNS
}
