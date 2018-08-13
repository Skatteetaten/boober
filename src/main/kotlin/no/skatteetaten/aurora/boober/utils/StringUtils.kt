package no.skatteetaten.aurora.boober.utils

import org.apache.commons.io.FilenameUtils

fun String.ensureEndsWith(endsWith: String, seperator: String = ""): String {
    if (this.endsWith(endsWith)) {
        return this
    }
    return "$this$seperator$endsWith"
}

fun String.ensureStartWith(startWith: String, seperator: String = ""): String {
    if (this.startsWith(startWith)) {
        return this
    }
    return "$startWith$seperator$this"
}

fun String.removeExtension(): String = FilenameUtils.removeExtension(this)

fun <R> String.withNonBlank(block: (String) -> R?): R? {

    if (this.isBlank()) {
        return null
    }
    return block(this)
}
