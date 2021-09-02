package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode

typealias Validator = (JsonNode?) -> Exception?

val defaultValidator: Validator = { null }

/**
  A handler into a given pointer into an AuroraConfig with default value and validation

  @param name: the name of the pointer, this can be database/foo/enabled
  @param validator: When extracting the value from this handler run this validator, if it returns an exception validation will fail
  @param defaultValue: if this handler is not specified use this default value
  @param defaultSource: what is the source of the default value, this can be used to say that a source is from the header
  @param canBeSimplifiedConfig: Some handlers can either be a boolean or an object. This marker is used to flag that behavior
 */
data class AuroraConfigFieldHandler(
    val name: String,
    // Dirty quick fix. This class should never be directly serialized to the http response.
    @JsonIgnore val validator: Validator = defaultValidator,
    val defaultValue: Any? = null,
    val defaultSource: String = "default",
    val canBeSimplifiedConfig: Boolean = false,

    // The file types this config field can be declared in. <code>null</code> means no restriction.
    val allowedFilesTypes: Set<AuroraConfigFileType>? = null,
    val validationSeverity: ErrorType = ErrorType.ILLEGAL
) {
    fun isAllowedFileType(fileType: AuroraConfigFileType) = allowedFilesTypes?.contains(fileType) ?: true
}
