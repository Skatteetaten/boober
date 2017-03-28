package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.Config
import no.skatteetaten.aurora.boober.model.Result
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException

/**
 * This service should probably not be called something with AOC in the name, since there will potentially be more
 * clients than AOC to these APIs. But the functionality of this class derives from the functionality found in AOC, so
 * AocService is a working title.
 */
@Service
class AocService(
        val configService: ConfigService,
        val validationService: ValidationService,
        val openshiftService: OpenshiftService,
        val openshiftClient: OpenshiftClient) {

    val logger: Logger = LoggerFactory.getLogger(this.javaClass)

    fun executeSetup(token: String, cmd: SetupCommand): Result {

        //TODO switch on what is available in the command.
        val res: Config = configService.createConfigFromAocConfigFiles(cmd.env, cmd.app!!, cmd.files!!)

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
                    val response = openshiftClient.save(url, it.value, token)
                    httpResult = response?.body


                } catch(e: HttpClientErrorException) {
                    val message = "Error saving url=$url, with message=${e.message}"
                    logger.debug(message)
                    httpErrors.plus(message)
                }
                Thread.sleep(1000)
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