package no.skatteetaten.aurora.boober.utils

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