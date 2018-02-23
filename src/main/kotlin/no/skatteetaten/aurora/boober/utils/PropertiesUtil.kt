package no.skatteetaten.aurora.boober.utils

import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.support.PropertiesLoaderUtils
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Properties

// function that filters out everything except the lines with the provided keys from a .properties ByteArray
fun filterProperties(properties: ByteArray, keys: List<String>): ByteArray =
  try {
      PropertiesLoaderUtils
        .loadProperties(ByteArrayResource(properties))
        .filter(keys)
        .toByteArray()
  } catch (ioe: IOException) {
      throw PropertiesException("Encountered a problem while reading properties.", ioe)
  }

fun Properties.filter(keys: List<String>): Properties {
    val propertyNames = this.stringPropertyNames()
    val newProps = Properties()
    keys.filter { propertyNames.contains(it) }
      .map { it to this.getProperty(it) }
      .forEach { (key, value) -> newProps.put(key, value) }
    return newProps
}

fun Properties.toByteArray(): ByteArray = ByteArrayOutputStream()
  .let { baos ->
      this.store(baos, "Properties filtered.")
      baos.toByteArray()
  }

class PropertiesException(override val message: String, override val cause: Throwable) : RuntimeException(message, cause)
