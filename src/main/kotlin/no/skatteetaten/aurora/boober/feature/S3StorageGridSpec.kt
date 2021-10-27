package no.skatteetaten.aurora.boober.feature

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec

data class S3ObjectArea(
    val tenant: String,
    val bucketName: String,
    val specifiedAreaKey: String,
    val area: String = specifiedAreaKey
)

val AuroraDeploymentSpec.s3ObjectAreas
    get(): List<S3ObjectArea> {

        val tenantName = "$affiliation-$cluster"
        val defaultBucketName: String = this["$FEATURE_DEFAULTS_FIELD_NAME/bucketName"]
        val defaultObjectAreaName =
            this.get<String>("$FEATURE_DEFAULTS_FIELD_NAME/objectArea").takeIf { it.isNotBlank() } ?: "default"

        return if (this.isSimplifiedAndEnabled(FEATURE_FIELD_NAME)) {
            val defaultS3Bucket = S3ObjectArea(
                tenant = tenantName,
                bucketName = defaultBucketName,
                specifiedAreaKey = defaultObjectAreaName
            )
            listOf(defaultS3Bucket)
        } else {
            val objectAreaNames = getSubKeyValues(FEATURE_FIELD_NAME)
            objectAreaNames
                .filter { objectAreaName -> this["$FEATURE_FIELD_NAME/$objectAreaName/enabled"] }
                .map { objectAreaName ->
                    S3ObjectArea(
                        tenant = tenantName,
                        bucketName = getOrNull("$FEATURE_FIELD_NAME/$objectAreaName/bucketName") ?: defaultBucketName,
                        specifiedAreaKey = objectAreaName,
                        area = this["$FEATURE_FIELD_NAME/$objectAreaName/objectArea"]
                    )
                }
        }
    }

fun AuroraDeploymentSpec.validateS3(): List<IllegalArgumentException> {

    val objectAreas = this.s3ObjectAreas
    if (objectAreas.isEmpty()) return emptyList()

    val requiredFieldsExceptions = objectAreas.validateRequiredFieldsArePresent()
    val duplicateObjectAreaInSameBucketExceptions = objectAreas.verifyObjectAreasAreUnique()
    val bucketNameExceptions = objectAreas.validateBucketNames()

    return requiredFieldsExceptions + duplicateObjectAreaInSameBucketExceptions + bucketNameExceptions
}

private fun List<S3ObjectArea>.validateBucketNames() = runValidators(
    {
        if (!Regex("[a-z0-9-.]*").matches(it.bucketName))
            "s3 bucketName can only contain lower case characters, numbers, hyphen(-) or period(.), specified value was: \"${it.bucketName}\""
        else null
    }, { s3ObjectArea ->
    "${s3ObjectArea.tenant}-${s3ObjectArea.bucketName}"
        .takeIf { it.length < 3 || it.length >= 63 }
        ?.let { "combination of bucketName and tenantName must be between 3 and 63 chars, specified value was ${it.length} chars long" }
}
)

private fun List<S3ObjectArea>.validateRequiredFieldsArePresent() = runValidators(
    { if (it.bucketName.isEmpty()) "Missing field: bucketName for s3" else null },
    { if (it.area.isEmpty()) "Missing field: objectArea for s3" else null }
)

private fun List<S3ObjectArea>.verifyObjectAreasAreUnique(): List<IllegalArgumentException> {
    return groupBy { it.area }
        .mapValues { it.value.size }
        .filter { it.value > 1 }
        .map { (name, count) -> IllegalArgumentException("objectArea name=$name used $count times for same application") }
}

private fun <T> List<T>.runValidators(vararg validators: (T) -> String?) =
    validators.flatMap { validator -> this.mapNotNull(validator) }.map { IllegalArgumentException(it) }

private const val FEATURE_FIELD_NAME = "s3"
private const val FEATURE_DEFAULTS_FIELD_NAME = "s3Defaults"
