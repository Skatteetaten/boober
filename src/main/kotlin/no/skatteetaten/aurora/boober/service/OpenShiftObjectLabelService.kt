package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import org.springframework.stereotype.Service

@Service
class OpenShiftObjectLabelService(private val userDetailsProvider: UserDetailsProvider) {

    companion object {
        @JvmStatic
        val MAX_LABEL_VALUE_LENGTH = 63

        /**
         * Returns a new Map where each value has been truncated as to not exceed the
         * <code>MAX_LABEL_VALUE_LENGTH</code> max length.
         * Truncation is done by cutting of characters from the start of the value, leaving only the last
         * MAX_LABEL_VALUE_LENGTH characters.
         */
        fun toOpenShiftLabelNameSafeMap(labels: Map<String, String>): Map<String, String> =
                labels.mapValues { toOpenShiftSafeLabel(it.value) }

        fun toOpenShiftSafeLabel(value: String): String {
            val startIndex = (value.length - MAX_LABEL_VALUE_LENGTH).takeIf { it >= 0 } ?: 0
            return value.substring(startIndex)
        }
    }

    fun createCommonLabels(auroraDeploymentSpec: AuroraDeploymentSpec, deployId: String,
                           additionalLabels: Map<String, String> = mapOf(), name: String = auroraDeploymentSpec.name): Map<String, String> {
        val labels = mapOf(
                "app" to name,
                "updatedBy" to userDetailsProvider.getAuthenticatedUser().username.replace(":", "-"),
                "affiliation" to auroraDeploymentSpec.affiliation,
                "booberDeployId" to deployId
        )
        return toOpenShiftLabelNameSafeMap(labels + additionalLabels)
    }
}
