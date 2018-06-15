package no.skatteetaten.aurora.boober.utils

fun <K, V> Map<K, V>.addIfNotNull(value: Pair<K, V>?): Map<K, V> {
    return value?.let {
        this + it
    } ?: this
}

fun <K, V> Map<K, V>.addIfNotNull(value: Map<K, V>?): Map<K, V> {
    return value?.let {
        this + it
    } ?: this
}

fun <T> Set<T>.addIfNotNull(value: T?): Set<T> {
    return value?.let {
        this + it
    } ?: this
}

fun <T> Set<T>.addIfNotNull(value: Set<T>?): Set<T> {
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

inline fun <K, V> Map<out K, V?>.filterNullValues(): Map<K, V> {
    val result = LinkedHashMap<K, V>()
    for (entry in this) {
        entry.value?.let {
            result[entry.key] = it
        }
    }
    return result
}

fun <T> Collection<T>?.takeIfNotEmpty(): Collection<T>? {
    return this.takeIf { it?.isEmpty() == false }
}

fun <K, V> Map<K, V>?.takeIfNotEmpty(): Map<K, V>? {
    return this.takeIf { it?.isEmpty() == false }
}