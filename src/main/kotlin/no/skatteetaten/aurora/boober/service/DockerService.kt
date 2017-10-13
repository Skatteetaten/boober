package no.skatteetaten.aurora.boober.service


import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.RequestEntity
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.net.URI

data class TagCommand @JvmOverloads constructor(
        val name: String,
        val from: String,
        val to: String,
        val fromRegistry: String,
        val toRegistry: String = fromRegistry)

@Service
class DockerService(val httpClient: RestTemplate) {

    val DOCKER_MANIFEST_V2: MediaType = MediaType.valueOf("application/vnd.docker.distribution.manifest.v2+json")

    fun tag(cmd: TagCommand): ResponseEntity<JsonNode> {

        //TODO:ErrorHandling
        val manifest = getManifest(cmd.fromRegistry, cmd.name, cmd.from)
        if (manifest.statusCode.is2xxSuccessful && manifest.hasBody()) {
            return putManifest(cmd.toRegistry, cmd.name, cmd.to, manifest.body)
        }

        return manifest


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

    fun generateManifestURI(registryUrl: String, name: String, tag: String) = URI("https://$registryUrl/v2/$name/manifests/$tag")


}