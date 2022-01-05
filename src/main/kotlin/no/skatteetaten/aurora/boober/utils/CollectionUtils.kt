package no.skatteetaten.aurora.boober.utils

import io.fabric8.kubernetes.api.model.HasMetadata
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import no.skatteetaten.aurora.boober.controller.security.SpringSecurityThreadContextElement
import no.skatteetaten.aurora.boober.model.AuroraResource
import kotlin.reflect.KClass

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

fun Map<String, String>.normalizeLabels(): Map<String, String> {
    val MAX_LABEL_VALUE_LENGTH = 63

    val LABEL_PATTERN = "(([A-Za-z0-9][-A-Za-z0-9_.]*)?[A-Za-z0-9])?"

    /**
     * Returns a new Map where each value has been truncated as to not exceed the
     * <code>MAX_LABEL_VALUE_LENGTH</code> max length.
     * Truncation is done by cutting of characters from the start of the value, leaving only the last
     * MAX_LABEL_VALUE_LENGTH characters.
     */
    fun toOpenShiftLabelNameSafeMap(labels: Map<String, String>): Map<String, String> {
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
        return labels.mapValues { toOpenShiftSafeLabel(it.value) }
    }
    return toOpenShiftLabelNameSafeMap(this)
}

fun MutableMap<String, Any>.deepSet(parts: List<String>, value: Map<String, Any>) {

    if (parts.isEmpty()) {
        value.forEach {
            this[it.key] = it.value
        }
        return
    }

    val emptyMap: MutableMap<String, Any> = mutableMapOf()
    val key = parts.first()
    val subMap: MutableMap<String, Any> = this.getOrDefault(key, emptyMap) as MutableMap<String, Any>
    this[parts.first()] = subMap
    subMap.deepSet(parts.drop(1), value)
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

fun <T> List<T>.prependIfNotNull(value: T?): List<T> {
    return value?.let { listOf(it) + this } ?: this
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

fun <K, V> Map<out K, V?>.filterNullValues(): Map<K, V> {
    val result = LinkedHashMap<K, V>()
    for (entry in this) {
        entry.value?.let {
            result[entry.key] = it
        }
    }
    return result
}

fun <T : Any, U : Any> List<Pair<T, U>>.toMultiMap() =
    this.groupBy(keySelector = { it.first }) { it.second }

fun <T : Collection<*>> T?.takeIfNotEmpty(): T? {
    return this.takeIf { it?.isEmpty() == false }
}

fun <K, V> Map<K, V>?.takeIfNotEmpty(): Map<K, V>? {
    return this.takeIf { it?.isEmpty() == false }
}

fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> {
    val iter = this
    return runBlocking(
        threadPool + MDCContext() + SpringSecurityThreadContextElement()
    ) {
        iter.pmap(f)
    }
}

val threadPool = newFixedThreadPoolContext(6, "boober")

// https://jivimberg.io/blog/2018/05/04/parallel-map-in-kotlin/
suspend fun <A, B> Iterable<A>.pmap(f: suspend (A) -> B): List<B> = coroutineScope {
    map { async { f(it) } }.awaitAll()
}

inline fun <reified T : HasMetadata> Collection<AuroraResource>.findResourceByType(): T =
    this.findResourceByType(T::class).firstOrNull() ?: throw Exception("No resource of specified type found")

inline fun <reified T : HasMetadata> Collection<AuroraResource>.findResourcesByType(suffix: String? = null): List<T> =
    this.findResourceByType(T::class).filter { item ->
        suffix?.let {
            item.metadata.name.endsWith(it)
        } ?: true
    }

@Suppress("UNCHECKED_CAST")
fun <T : Any> Collection<AuroraResource>.findResourceByType(kclass: KClass<T>): List<T> =
    filter { it.resource::class == kclass }
        .map { it.resource as T }
