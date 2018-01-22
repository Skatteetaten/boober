package no.skatteetaten.aurora.boober.utils

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.*


// function that filters out everything except the lines with the provided keys from a .properties ByteArray
fun filterProperties(properties: ByteArray, keys: List<String>) : ByteArray {
    Properties().let { props ->
        try {
            props.load(ByteArrayInputStream(properties))
        } catch(ioe : IOException) {
            throw PropertiesException("Encountered a problem while reading properties.", ioe)
        } catch(iae : IllegalArgumentException) {
            throw PropertiesException("Encountered a problem while reading properties. The input stream contains a " +
                    "malformed Unicode escape sequence.", iae)
        }

        Properties().let { newProps ->
            keys.forEach( { key ->
                if (props.stringPropertyNames().contains(key)) {
                    props.getProperty(key).let { newProps.put(key, it) }
                }
            })
            ByteArrayOutputStream().let { baos ->
                newProps.store(baos, "Properties filtered.")
                return baos.toByteArray()
            }
        }
    }
}

class PropertiesException(override val message:String, override val cause : Throwable): RuntimeException(message, cause)
