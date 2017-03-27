package no.skatteetaten.aurora.boober.controller


import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.Result
import no.skatteetaten.aurora.boober.service.ConfigService
import no.skatteetaten.aurora.boober.service.OpenshiftClient
import no.skatteetaten.aurora.boober.service.OpenshiftService
import no.skatteetaten.aurora.boober.service.ValidationService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.HttpClientErrorException

@RestController
class ExecuteController(val configService: ConfigService,
                        val validationService: ValidationService,
                        val openshiftService: OpenshiftService,
                        val openshiftClient: OpenshiftClient) {

    val logger: Logger = LoggerFactory.getLogger(ExecuteController::class.java)

    @PutMapping("/setup")
    fun setup(@RequestHeader(value = "Authentication") rawToken: String,
              @RequestBody cmd: SetupCommand): Result {

        val token = rawToken.split(" ")[1]

        logger.info("Setting up ${cmd.app} in ${cmd.env} with token $token")
        //TODO swith on what is avilable in the command.
        val res = configService.createBooberResult(cmd.env, cmd.app!!, cmd.files!!)

        val validated = validationService.validate(res, token)
        //TODO perform operations, maybe expand Result object here?

        if (!validated.valid) {
            return validated
        }

        val objects = openshiftService.generateObjects(res, token)

        /* This really should not be in the controller layer */
        if (objects.openshiftObjects != null && objects.config != null) {

            //race condition if we create resources to fast

            val httpErrors: List<String> = listOf()
            var httpResult: JsonNode? = null
            val results = objects.openshiftObjects.map {
                val url = createOpenshiftUrl(it.key, objects.config.namespace)
                try {
                    httpResult = openshiftClient.save(url, it.value, token)

                } catch(e: HttpClientErrorException) {
                    val message = "Error saving url=$url, with message=${e.message}"
                    logger.debug(message)
                    httpErrors.plus(message)
                }
                Pair(it.key, httpResult)

            }.toMap()

            return objects.copy(savedOjbects = results, errors = httpErrors)
        }
        return objects

    }

    private fun createOpenshiftUrl(key: String, namespace: String): String {

        if (key == "projects") {
            return "/oapi/v1/projects"
        }

        val prefix = if (key in listOf("services", "configmaps")) {
            "/api"
        } else {
            "/oapi"
        }

        return "$prefix/v1/namespaces/$namespace/$key"

    }

}

data class SetupCommand(val affiliation: String,
                        val env: String,
                        val app: String?,
                        val files: Map<String, JsonNode>?,
                        val overrides: Map<String, JsonNode>?)