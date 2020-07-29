package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.utils.RetryingRestTemplateWrapper
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate

@Component
class BitbucketRestTemplateWrapper(@Qualifier("bitbucket") restTemplate: RestTemplate) :
    RetryingRestTemplateWrapper(restTemplate)

private val logger = KotlinLogging.logger {}

@Service
class BitbucketService(
    val restTemplateWrapper: BitbucketRestTemplateWrapper
) {

    fun uploadFile(project: String, repo: String, fileName: String, message: String, content: String): String? {

        val url = "/rest/api/1.0/projects/$project/repos/$repo/browse/$fileName"
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
        }

        val body = LinkedMultiValueMap<String, String>().apply {
            add("message", message)
            add("content", content)
        }

        return restTemplateWrapper.put(body, headers, String::class, url).body
    }

    fun getFiles(project: String, repo: String, prefix: String): List<String> {
        val url = "/rest/api/1.0/projects/$project/repos/$repo/files/$prefix?limit=100000"

        return restTemplateWrapper.get(JsonNode::class, url).body?.let { jsonNode ->
            val values = jsonNode["values"] as ArrayNode
            values.map { it.asText() }
        } ?: emptyList()
    }

    fun getFile(project: String, repo: String, fileName: String, ref: String = "master"): String? {
        val url = "/projects/$project/repos/$repo/raw/$fileName?at=$ref"
        logger.debug("getting bitbucket file for Path=$url")
        return restTemplateWrapper.get(String::class, url).body
    }

    fun getRepoNames(project: String): List<String> {

        val url = "/rest/api/1.0/projects/$project/repos?limit=1000"
        val repoList = restTemplateWrapper.get(JsonNode::class, url).body
        val values = repoList!!["values"] as ArrayNode

        return values.map {
            it["slug"].textValue()
        }
    }
}
