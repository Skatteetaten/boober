package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okio.Buffer
import org.springframework.util.ResourceUtils
import java.net.URL

open class ResourceLoader {

    fun loadResource(resourceName: String, folder: String = this.javaClass.simpleName): String =
        getResourceUrl(resourceName, folder).readText()

    fun getResourceUrl(resourceName: String, folder: String = this.javaClass.simpleName): URL {
        val pck = this.javaClass.`package`.name.replace(".", "/")
        val path = "src/test/resources/$pck/$folder/$resourceName"
        return ResourceUtils.getURL(path)
    }

    fun loadJsonResource(resourceName: String): JsonNode =
        jacksonObjectMapper().readValue(loadResource(resourceName))

    fun loadByteResource(resourceName: String, folder: String = this.javaClass.simpleName): ByteArray {
        return getResourceUrl(resourceName, folder).openStream().readBytes()
    }

    fun loadBufferResource(resourceName: String, folder: String = this.javaClass.simpleName): Buffer {
        return Buffer().readFrom(getResourceUrl(resourceName, folder).openStream())
    }


}