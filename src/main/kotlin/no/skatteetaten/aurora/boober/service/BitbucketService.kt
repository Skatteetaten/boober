package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

@Service
class BitbucketService(@Qualifier("bitbucket") val restTemplate: RestTemplate) {

    fun auroraConfigs() : List<String> {

        val repoList = restTemplate.getForObject("/projects/ac/repos?limit=1000", JsonNode::class.java)
        val values=repoList["values"] as ArrayNode

        return values.map {
            it["slug"].textValue()
        }
    }
}