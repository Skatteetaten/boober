package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestTemplate

@Service
class BitbucketService(
    @Qualifier("bitbucket") val restTemplate: RestTemplate,
    val mapper: ObjectMapper
) {

    val logger: Logger = LoggerFactory.getLogger(BitbucketService::class.java)

    fun uploadFile(project: String, repo: String, fileName: String, message: String, content: String): String? {

        val url = "/rest/api/1.0/projects/$project/repos/$repo/browse/{fileName}"
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
        }

        val map = LinkedMultiValueMap<String, String>()
        map.add("message", message)
        map.add("content", content)

        val request = HttpEntity<MultiValueMap<String, String>>(map, headers)

        return restTemplate.exchange(url, HttpMethod.PUT, request, String::class.java, fileName).body
    }

    fun getFiles(project: String, repo: String, prefix: String): List<String> {
        val url = "/rest/api/1.0/projects/$project/repos/$repo/files/{prefix}?limit=100000"
        return restTemplate.getForObject(url, JsonNode::class.java, prefix)?.let {
            val values = it["values"] as ArrayNode
            values.map { it.asText() }
        } ?: emptyList()
    }

    fun getFile(project: String, repo: String, fileName: String): String? {
        val url = "/projects/$project/repos/$repo/raw/{fileName}"
        return restTemplate.getForObject(url, String::class.java, fileName)
    }

    fun getRepoNames(project: String): List<String> {

        val repoList =
            restTemplate.getForObject("/rest/api/1.0/projects/$project/repos?limit=1000", JsonNode::class.java)
        val values = repoList["values"] as ArrayNode

        return values.map {
            it["slug"].textValue()
        }
    }
}
