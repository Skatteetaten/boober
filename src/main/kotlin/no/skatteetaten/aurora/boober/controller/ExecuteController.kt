package no.skatteetaten.aurora.boober.controller


import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.Result
import no.skatteetaten.aurora.boober.service.ConfigService
import no.skatteetaten.aurora.boober.service.OpenshiftService
import no.skatteetaten.aurora.boober.service.ValidationService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

@RestController
class ExecuteController(val configService: ConfigService, val validationService: ValidationService, val openshiftService:OpenshiftService) {

    val logger: Logger = LoggerFactory.getLogger(ExecuteController::class.java)

    @PutMapping("/setup")
    fun setup(@RequestHeader(value="Authentication") token:String,
              @RequestBody cmd:SetupCommand): Result {

        logger.info("Setting up ${cmd.app!!} in ${cmd.env} with token $token")
        //TODO swith on what is avilable in the command.
        val res = configService.createBooberResult(cmd.env, cmd.app!!, cmd.files!!)

        val validated = validationService.validate(res, token)
        //TODO perform operations, maybe expand Result object here?

        if(!validated.valid) {
            return validated
        }

        return openshiftService.execute(res, token)
    }

}

data class SetupCommand(val affiliation:String, val env:String, val app:String?, val files: Map<String,JsonNode>?, val overrides:Map<String, JsonNode>?)


