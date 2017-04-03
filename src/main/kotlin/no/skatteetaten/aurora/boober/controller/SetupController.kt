package no.skatteetaten.aurora.boober.controller


import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.service.ApplicationResult
import no.skatteetaten.aurora.boober.service.AuroraConfigParserService
import no.skatteetaten.aurora.boober.service.SetupService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import no.skatteetaten.aurora.boober.service.ValidationException
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class SetupController(val setupService: SetupService, val auroraConfigParserService: AuroraConfigParserService) {

    @PutMapping("/setup")
    fun setup(@AuthenticationPrincipal activeUser: User, @RequestBody cmd: SetupCommand): Response {

        val token = rawToken.split(" ")[1]

        val auroraConfig = AuroraConfig(cmd.files)

        val auroraDcs: List<Any> = auroraConfig.environments.flatMap { env ->
            auroraConfig.applications.map { app ->
                try {
                    auroraConfigParserService.createAuroraDcFromAuroraConfig(auroraConfig, env, app)
                } catch (ex: ValidationException) {
                    mapOf("message" to ex.message, "errors" to ex.errors)
                }
            }
        }

        val applicationResults: List<ApplicationResult> = auroraDcs.flatMap {
            if (it is AuroraDeploymentConfig) setupService.executeSetup(token, it)
            else listOf()
        }

        val validationErrors = auroraDcs.filter { it is Map<*, *> }

        return Response(success = validationErrors.isEmpty() && applicationResults.none { it.containsError },
                        items = applicationResults + validationErrors)
    }
}

data class SetupCommand(val affiliation: String,
                        val env: String,
                        val app: String?,
                        val files: Map<String, Map<String, Any?>>,
                        val overrides: Map<String, Map<String, Any?>>?)