package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

@Service
class BitbucketProjectService(
    @Qualifier("bitbucket") val restTemplate: RestTemplate,
    @Value("\${boober.bitbucket.project}") val project: String
) {

    fun getAllSlugs(): List<String> {

        val repoList =
            restTemplate.getForObject("rest/api/1.0/projects/$project/repos?limit=1000", JsonNode::class.java)
        val values = repoList["values"] as ArrayNode

        return values.map {
            it["slug"].textValue()
        }
    }
}