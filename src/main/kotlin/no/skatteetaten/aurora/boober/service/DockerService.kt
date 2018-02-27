package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

data class TagResult(val cmd: TagCommand, val response: ResponseEntity<JsonNode>, val success: Boolean)

data class TagCommand @JvmOverloads constructor(
    val name: String,
    val from: String,
    val to: String,
    val fromRegistry: String,
    val toRegistry: String = fromRegistry)

@Service
class DockerService(@Qualifier("docker") val httpClient: RestTemplate) {

    val DOCKER_MANIFEST_V2: MediaType = MediaType.valueOf("application/vnd.docker.distribution.manifest.v2+json")

    fun tag(cmd: TagCommand): TagResult {
        val manifest = getManifest(cmd.fromRegistry, cmd.name, cmd.from)
        val response = if (manifest.statusCode.is2xxSuccessful && manifest.hasBody()) {
            putManifest(cmd.toRegistry, cmd.name, cmd.to, manifest.body)
        } else manifest
        return TagResult(cmd, response, response.statusCode.is2xxSuccessful)
    }

    fun getManifest(registryUrl: String, name: String, tag: String): ResponseEntity<JsonNode> {
        val headers = HttpHeaders().apply {
            accept = listOf(DOCKER_MANIFEST_V2)
        }

        return httpClient.exchange(
            "https://$registryUrl/v2/{name}/manifests/{tag}",
            HttpMethod.GET,
            HttpEntity<JsonNode>(headers),
            JsonNode::class.java,
            mapOf("name" to name, "tag" to tag))
    }

    fun putManifest(registryUrl: String, name: String, tag: String, payload: JsonNode): ResponseEntity<JsonNode> {
        val headers = HttpHeaders().apply {
            contentType = DOCKER_MANIFEST_V2
        }
        return httpClient.exchange(
            "https://$registryUrl/v2/{name}/manifests/{tag}",
            HttpMethod.PUT,
            HttpEntity(payload, headers),
            JsonNode::class.java,
            mapOf("name" to name, "tag" to tag))
    }
}

fun String.dockerGroupSafeName() = this.replace(".", "_")