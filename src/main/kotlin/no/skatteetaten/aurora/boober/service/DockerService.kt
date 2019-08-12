package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.ServiceTypes.CANTUS
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.utils.RetryingRestTemplateWrapper
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
class DockerService(
    val client: CantusRestTemplateWrapper
) {
    fun tag(cmd: TagCommand): TagResult {
        val cantusCmd = CantusTagCommand(
            "${cmd.fromRegistry}/${cmd.name}:${cmd.from}".replace(".", "-"),
            "${cmd.toRegistry}/${cmd.name}:${cmd.to}".replace(".", "-")
        )
        val resultEntity = client.post(body = cantusCmd, type = JsonNode::class, url = "/tag")
        return TagResult(cmd, resultEntity.body, resultEntity.statusCode.is2xxSuccessful)
    }
}
fun String.dockerGroupSafeName() = this.replace(".", "_")