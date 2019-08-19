package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.ServiceTypes.CANTUS
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.utils.RetryingRestTemplateWrapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

data class TagResult(val cmd: TagCommand, val response: JsonNode?, val success: Boolean)

data class TagCommand @JvmOverloads constructor(
    val name: String,
    val from: String,
    val to: String,
    val fromRegistry: String,
    val toRegistry: String = fromRegistry
)

data class CantusTagCommand(
    val from: String,
    val to: String
)

@Component
class CantusRestTemplateWrapper(@TargetService(CANTUS) restTemplate: RestTemplate) :
    RetryingRestTemplateWrapper(restTemplate)

@Service
class CantusService(
    val client: CantusRestTemplateWrapper
) {

    val logger: Logger = LoggerFactory.getLogger(CantusService::class.java)

    fun tag(cmd: TagCommand): TagResult {
        val cantusCmd = CantusTagCommand(
            "${cmd.fromRegistry}/${cmd.name}:${cmd.from}",
            "${cmd.toRegistry}/${cmd.name}:${cmd.to}"
        )
        val resultEntity = client.post(body = cantusCmd, type = JsonNode::class, url = "/tag")
        logger.debug("Response from cantus code=${resultEntity.statusCode} body=${resultEntity.body}")
        return TagResult(cmd, resultEntity.body, resultEntity.statusCode.is2xxSuccessful)
    }
}

fun String.dockerGroupSafeName() = this.replace(".", "_")