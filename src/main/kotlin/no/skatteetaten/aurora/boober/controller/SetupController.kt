package no.skatteetaten.aurora.boober.controller


import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.service.ApplicationResult
import no.skatteetaten.aurora.boober.service.AuroraConfigParserService
import no.skatteetaten.aurora.boober.service.SetupService
import no.skatteetaten.aurora.boober.service.ValidationException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class SetupController(val setupService: SetupService, val auroraConfigParserService: AuroraConfigParserService) {

    @PutMapping("/setup")
    fun setup(@AuthenticationPrincipal activeUser: User, @RequestBody cmd: SetupCommand): Response {

        val auroraConfig = if (!cmd.app.isNullOrBlank()) {
            AuroraConfig(cmd.app!!, cmd.env, cmd.files)
        } else {
            AuroraConfig(env = cmd.env, aocConfigFiles = cmd.files)
        }

        val maybeAuroraDC: List<Any> = auroraConfig.applicationsToDeploy().map { (env, app) ->
            try {
                auroraConfigParserService.createAuroraDcFromAuroraConfig(auroraConfig, env, app)
            } catch (ex: ValidationException) {
                mapOf("message" to ex.message, "errors" to ex.errors)
            }
        }

        fun setupAuroraDeploymentConfig(maybeAuroraDc: List<Any>): List<ApplicationResult> {
            return maybeAuroraDc.flatMap {
                if (it is AuroraDeploymentConfig) setupService.executeSetup(activeUser.token, it)
                else listOf()
            }
        }

        val applicationResults = setupAuroraDeploymentConfig(maybeAuroraDC)
        val validationErrors = maybeAuroraDC.filter { it !is AuroraDeploymentConfig }

        return Response(success = validationErrors.isEmpty() && applicationResults.none { it.containsError },
                        items = applicationResults + validationErrors)
    }
}

data class SetupCommand(val affiliation: String,
                        val env: String,
                        val app: String?,
                        val files: Map<String, Map<String, Any?>>,
                        val overrides: Map<String, Map<String, Any?>>?)