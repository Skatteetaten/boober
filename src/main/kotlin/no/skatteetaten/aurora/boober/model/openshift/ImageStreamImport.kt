package no.skatteetaten.aurora.boober.model.openshift

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

// Only a partial representation of ImageStreamImport
// For more details: https://github.com/fabric8io/kubernetes-client/issues/1025
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ImageStreamImport(
    val metadata: Metadata? = null,
    val apiVersion: String = "v1",
    val kind: String = "ImageStreamImport",
    val spec: Spec? = null,
    val status: Status? = null
) {

    fun toJsonNode(): JsonNode =
        jacksonObjectMapper().valueToTree(this)

    fun findErrorMessage(tagName: String): String? {
        val errorStatuses = listOf("false", "failure")
        return this.status?.import?.status?.tags
            ?.firstOrNull { it.tag == tagName }
            ?.conditions
            ?.firstOrNull { errorStatuses.contains(it.status.toLowerCase()) }
            ?.message
    }

    fun isDifferentImage(imageHash: String?): Boolean =
        this.status?.import?.status?.tags?.firstOrNull()?.items?.firstOrNull()?.image
            ?.let { return it != imageHash } ?: true
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Metadata(val name: String = "")

@JsonIgnoreProperties(ignoreUnknown = true)
data class Spec(
    val import: Boolean = true,
    val images: List<ImagesItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ImagesItem(
    val from: From? = null,
    val to: To? = null,
    val importPolicy: ImportPolicy? = null,
    val tag: String? = null,
    val status: Status? = null
)

data class From(
    val kind: String = "",
    val name: String = ""
)

data class To(val name: String = "")

data class ImportPolicy(val scheduled: Boolean = false)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Status(
    val images: List<ImagesItem>? = emptyList(),
    val import: Import? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Import(
    val metadata: Metadata? = null,
    val spec: Spec? = null,
    val status: ImportStatus? = null
)

data class ImportStatus(
    val dockerImageRepository: String = "",
    val tags: List<TagsItem> = emptyList()
)

data class TagsItem(
    val tag: String = "",
    val items: List<ItemsItem>? = emptyList(),
    val conditions: List<ConditionsItem> = emptyList()
)

data class ItemsItem(
    val generation: Int = 0,
    val image: String = "",
    val created: String = "",
    val dockerImageReference: String = ""
)

data class ConditionsItem(
    val type: String = "",
    val status: String = "",
    val lastTransitionTime: String = "",
    val reason: String = "",
    val message: String = "",
    val generation: Int = 0
)
