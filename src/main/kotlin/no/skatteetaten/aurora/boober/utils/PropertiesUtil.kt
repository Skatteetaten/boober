package no.skatteetaten.aurora.boober.utils

import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.support.PropertiesLoaderUtils
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.*


fun filterProperties(properties: ByteArray, keys: List<String>, mappedKeys: Map<String, String>?): ByteArray =
        try {
            PropertiesLoaderUtils
                    .loadProperties(ByteArrayResource(properties))
                    .filter(keys)
                    .replaceMappedKeys(mappedKeys)
                    .toByteArray()
        } catch (ioe: IOException) {
            throw PropertiesException("Encountered a problem while reading properties.", ioe)
        }


fun Properties.filter(keys: List<String>): Properties {
    if (keys.isEmpty()) {
        return this
    }

    val propertyNames = this.stringPropertyNames()
    val newProps = Properties()
    keys.filter { propertyNames.contains(it) }
            .map { it to this.getProperty(it) }
            .forEach { (key, value) -> newProps[key] = value }
    return newProps
}

fun Properties.replaceMappedKeys(mappedKeys: Map<String, String>?): Properties {
    if (mappedKeys == null || mappedKeys.isEmpty()) {
        return this
    }

    val newProps = Properties()
    this.stringPropertyNames().forEach {
        val key = mappedKeys[it] ?: it
        newProps[key] = this.getProperty(it)
    }
    return newProps
}

fun Properties.toByteArray(): ByteArray = ByteArrayOutputStream()
        .let { baos ->
            this.store(baos, "Properties filtered.")
            baos.toByteArray()
        }

class PropertiesException(override val message: String, override val cause: Throwable) : RuntimeException(message, cause)
