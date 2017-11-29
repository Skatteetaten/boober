package no.skatteetaten.aurora.boober.service


import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.net.URI

data class TagResult(val cmd: TagCommand, val response: ResponseEntity<JsonNode>, val success: Boolean)

data class TagCommand @JvmOverloads constructor(
        val name: String,
        val from: String,
        val to: String,
        val fromRegistry: String,
        val toRegistry: String = fromRegistry)

@Service
class DockerService(val httpClient: RestTemplate) {

    val DOCKER_MANIFEST_V2: MediaType = MediaType.valueOf("application/vnd.docker.distribution.manifest.v2+json")

    fun tag(cmd: TagCommand): TagResult {
        val manifest = getManifest(cmd.fromRegistry, cmd.name, cmd.from)
        val response = if (manifest.statusCode.is2xxSuccessful && manifest.hasBody()) {
            putManifest(cmd.toRegistry, cmd.name, cmd.to, manifest.body)
        } else manifest
        return TagResult(cmd, response, response.statusCode.is2xxSuccessful)
    }

    fun getManifest(registryUrl: String, name: String, tag: String): ResponseEntity<JsonNode> {
        val manifestURI = generateManifestURI(registryUrl, name, tag)
        val headers = HttpHeaders()
        headers.accept = listOf(DOCKER_MANIFEST_V2)
        val req = RequestEntity<JsonNode>(headers, HttpMethod.GET, manifestURI)

        return httpClient.exchange(req, JsonNode::class.java)

    }

    fun putManifest(registryUrl: String, name: String, tag: String, payload: JsonNode): ResponseEntity<JsonNode> {
        val manifestURI = generateManifestURI(registryUrl, name, tag)
        val headers = HttpHeaders()
        headers.contentType = DOCKER_MANIFEST_V2
        val req = RequestEntity<JsonNode>(payload, headers, HttpMethod.PUT, manifestURI)
        return httpClient.exchange(req, JsonNode::class.java)

    }

    fun generateManifestURI(registryUrl: String, name: String, tag: String) = URI("https://$registryUrl/v1/$name/manifests/$tag")


}