package no.skatteetaten.aurora.boober.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
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
    fun postDeployResult(fileName: String, message: String, content: String): Boolean {

        val url = "rest/api/1.0/projects/$project/repos/$repo/browse/{fileName}"
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
        }

        val map = LinkedMultiValueMap<String, String>()
        map.add("message", message)
        map.add("content", content)

        val request = HttpEntity<MultiValueMap<String, String>>(map, headers)

        val response = restTemplate.postForEntity(url, request, String::class.java, fileName)
        logger.info("{}", response)
        return true
    }

    fun getDeployResult(fileName: String): DeployHistory? {
        return null
    }
}