package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestTemplate

@Service
class BitbucketDeploymentTagService(
    @Qualifier("bitbucket") val restTemplate: RestTemplate,
    @Value("\${boober.bitbucket.tags.project}") val project: String,
    @Value("\${boober.bitbucket.tags.repo}") val repo: String
) {

    val logger: Logger = LoggerFactory.getLogger(BitbucketDeploymentTagService::class.java)

    fun uploadFile(fileName: String, message: String, content: String): JsonNode? {

        val url = "rest/api/1.0/projects/$project/repos/$repo/browse/{fileName}"
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
        }

        val map = LinkedMultiValueMap<String, String>()
        map.add("message", message)
        map.add("content", content)

        val request = HttpEntity<MultiValueMap<String, String>>(map, headers)

        return restTemplate.postForObject(url, request, JsonNode::class.java, fileName)
    }


    // TODO: Paged api?
    fun getFiles(prefix: String): List<String> {
        val url="rest/api/1.0/projects/$project}/repos/$repo/files/{prefix}?limit=10000"
        return restTemplate.getForObject(url, JsonNode::class.java, prefix)?.let{
            val values = it["values"] as ArrayNode
            values.map { it.toString() }
        }?: emptyList()
    }

    inline final fun <reified T> getFile(fileName: String): T? {
        val url="projects/$project/repos/$repo/raw/{fileName}"
        return restTemplate.getForObject(url, T::class.java, fileName)
    }
}