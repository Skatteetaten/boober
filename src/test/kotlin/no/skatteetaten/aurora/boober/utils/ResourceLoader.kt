package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.skatteetaten.aurora.boober.mapper.platform.JavaPlatformHandler
import no.skatteetaten.aurora.boober.mapper.platform.WebPlatformHandler
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecService
import okio.Buffer
import org.junit.jupiter.api.BeforeEach
import org.springframework.util.ResourceUtils
import java.net.URL

open class ResourceLoader {

    // TODO: jeg får ikke denne til å funke med @Before
    @BeforeEach
    fun setupBefore() {
        AuroraDeploymentSpecService.APPLICATION_PLATFORM_HANDLERS =
            mapOf("java" to JavaPlatformHandler(), "web" to WebPlatformHandler())
    }

    fun loadResource(resourceName: String, folder: String = this.javaClass.simpleName): String =
        getResourceUrl(resourceName, folder).readText()

    fun getResourceUrl(resourceName: String, folder: String = this.javaClass.simpleName): URL {
        val pck = this.javaClass.`package`.name.replace(".", "/")
        val path = "src/test/resources/$pck/$folder/$resourceName"
        return ResourceUtils.getURL(path)
    }

    fun loadJsonResource(resourceName: String, folder: String = this.javaClass.simpleName): JsonNode =
        jacksonObjectMapper().readValue(loadResource(resourceName, folder))

    fun loadByteResource(resourceName: String, folder: String = this.javaClass.simpleName): ByteArray {
        return getResourceUrl(resourceName, folder).openStream().readBytes()
    }

    fun loadBufferResource(resourceName: String, folder: String = this.javaClass.simpleName): Buffer {
        return Buffer().readFrom(getResourceUrl(resourceName, folder).openStream())
    }
}