package no.skatteetaten.aurora.boober.utils

import no.skatteetaten.aurora.boober.model.ConfigFieldErrorDetail
import no.skatteetaten.aurora.boober.model.ErrorDetail
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.*

fun <K, V> Map<K, V>.addIfNotNull(value: Pair<K, V>?): Map<K, V> {
    return value?.let {
        this + it
    } ?: this
}

fun <T> List<T>.addIfNotNull(value: T?): List<T> {
    return value?.let {
        this + it
    } ?: this
}

fun <T> List<T>.addIfNotNull(value: List<T>?): List<T> {
    return value?.let {
        this + it
    } ?: this
}

fun <T> List<T>.addIf(condition: Boolean, value: T): List<T> = if (condition) this + listOf(value) else this

fun <T> List<T>.addIf(condition: Boolean, values: List<T>): List<T> = if (condition) this + values else this


fun <K, V> Map<K, V>?.nullOnEmpty(): Map<K, V>? {
    if (this == null) {
        return this
    }
    if (this.isEmpty()) {
        return null
    }

    return this
}

fun <T> Collection<T>?.nullOnEmpty(): Collection<T>? {
    if (this == null) {
        return this
    }
    if (this.isEmpty()) {
        return null
    }

    return this
}


