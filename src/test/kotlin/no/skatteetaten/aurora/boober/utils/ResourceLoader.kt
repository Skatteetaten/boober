package no.skatteetaten.aurora.boober.utils

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URL
import okio.Buffer
import org.springframework.util.ResourceUtils

open class ResourceLoader {

    fun loadResource(resourceName: String, folder: String = this.javaClass.simpleName): String =
        getResourceUrl(resourceName, folder).readText()

    // TODO: should this not use package name to make it easier to reuse files
    fun getResourceUrl(resourceName: String, folder: String = this.javaClass.simpleName): URL {
        val pck = this.javaClass.`package`.name.replace(".", "/")
        val path = "src/test/resources/$pck/$folder/$resourceName"
        return ResourceUtils.getURL(path)
    }

    inline fun <reified T> load(resourceName: String, folder: String = this.javaClass.simpleName): T =
            jacksonObjectMapper().readValue(loadResource(resourceName, folder))

    fun loadJsonResource(resourceName: String, folder: String = this.javaClass.simpleName): JsonNode =
        jacksonObjectMapper().readValue(loadResource(resourceName, folder))

    fun loadByteResource(resourceName: String, folder: String = this.javaClass.simpleName): ByteArray {
        return getResourceUrl(resourceName, folder).openStream().readBytes()
    }

    fun loadBufferResource(resourceName: String, folder: String = this.javaClass.simpleName): Buffer {
        return Buffer().readFrom(getResourceUrl(resourceName, folder).openStream())
    }
}

// This is done as text comparison and not jsonNode equals to get easier diff when they dif
fun compareJson(expected: JsonNode, actual: JsonNode, name: String? = null): Boolean {
    val writer = jsonMapper().writerWithDefaultPrettyPrinter()
    val targetString = writer.writeValueAsString(actual)
    val nodeString = writer.writeValueAsString(expected)
    assertThat(targetString, name).isEqualTo(nodeString)
    return true
}
