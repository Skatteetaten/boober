package no.skatteetaten.aurora.boober.service

import org.springframework.stereotype.Service

// TODO: refactor to extention method on Map<String, String> and ensure that it is used everywhere we add labels

@Service
class OpenShiftObjectLabelService(private val userDetailsProvider: UserDetailsProvider) {

    companion object {
        @JvmStatic
        val MAX_LABEL_VALUE_LENGTH = 63

        @JvmStatic
        val LABEL_PATTERN = "(([A-Za-z0-9][-A-Za-z0-9_.]*)?[A-Za-z0-9])?"

        /**
         * Returns a new Map where each value has been truncated as to not exceed the
         * <code>MAX_LABEL_VALUE_LENGTH</code> max length.
         * Truncation is done by cutting of characters from the start of the value, leaving only the last
         * MAX_LABEL_VALUE_LENGTH characters.
         */
        fun toOpenShiftLabelNameSafeMap(labels: Map<String, String>): Map<String, String> =
            labels.mapValues { toOpenShiftSafeLabel(it.value) }

        @JvmStatic
        fun toOpenShiftSafeLabel(value: String): String {
            val startIndex = (value.length - MAX_LABEL_VALUE_LENGTH).takeIf { it >= 0 } ?: 0

            var tail = value.substring(startIndex)
            while (true) {
                val isLegal = tail.matches(Regex(LABEL_PATTERN))
                if (isLegal) break
                tail = tail.substring(1)
            }
            return tail
        }
    }
}
