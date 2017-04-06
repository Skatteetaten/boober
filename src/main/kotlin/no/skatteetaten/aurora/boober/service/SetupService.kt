package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

data class ApplicationId(
        val environmentName: String,
        val applicationName: String
)

data class ApplicationResult(
        val applicationId: ApplicationId,
        val auroraDc: AuroraDeploymentConfig,
        val openShiftResponses: List<OpenShiftResponse> = listOf()
)

data class Error(
        val applicationId: ApplicationId,
        val errors: List<String> = listOf()
)

@Service
class SetupService(
        val auroraConfigParserService: AuroraConfigParserService,
        val openShiftService: OpenShiftService,
        val openShiftClient: OpenShiftClient) {

    val logger: Logger = LoggerFactory.getLogger(SetupService::class.java)

    fun executeSetup(auroraConfig: AuroraConfig, envs: List<String>, apps: List<String>, dryRun: Boolean = false): List<ApplicationResult> {

        val applicationIds: List<ApplicationId> = envs.flatMap { env -> apps.map { app -> ApplicationId(env, app) } }
        val auroraDcs: MutableList<AuroraDeploymentConfig> = createAuroraDcsForApplications(auroraConfig, applicationIds)

        return auroraDcs.map { applyDeploymentConfig(it, dryRun) }
    }

    private fun createAuroraDcsForApplications(auroraConfig: AuroraConfig, applicationIds: List<ApplicationId>): MutableList<AuroraDeploymentConfig> {

        val auroraDcs: MutableList<AuroraDeploymentConfig> = mutableListOf()
        val errors: MutableList<Error> = mutableListOf()

        applicationIds.forEach { aid ->
            try {
                val mergedFileForApplication = auroraConfig.getMergedFileForApplication(aid)
                val secrets = mergedFileForApplication.s("secretFolder")?.let {
                    val secrets = auroraConfig.getSecrets(it)
                    if (secrets.isEmpty()) {
                        errors.add(Error(aid, listOf("No secret files with prefix $it")))
                    }
                    secrets
                }
                val auroraDc = auroraConfigParserService.createAuroraDcFromMergedFileForApplication(mergedFileForApplication, secrets)

                auroraDc.groups?.filter {
                    !openShiftClient.isValidGroup(it)
                }?.let {
                    errors.add(Error(aid, listOf("The following groups are not valid= ${it.joinToString()}")))
                }

                auroraDc.users?.filter {
                    !openShiftClient.isValidUser(it)
                }?.let {
                    errors.add(Error(aid, listOf("The following users are not valid= ${it.joinToString()}")))

                }
                auroraDcs.add(auroraDc)
            } catch (e: ApplicationConfigException) {
                errors.add(Error(aid, e.errors))
            }
        }

        if (errors.isNotEmpty()) {
            throw AuroraConfigException("AuroraConfig contained errors for one or more applications", errors)
        }

        return auroraDcs
    }

    private fun applyDeploymentConfig(adc: AuroraDeploymentConfig, dryRun: Boolean = false): ApplicationResult {

        logger.info("Creating OpenShift objects for application ${adc.name} in namespace ${adc.namespace}")
        val openShiftObjects: List<JsonNode> = openShiftService.generateObjects(adc)
        val openShiftResponses: List<OpenShiftResponse> = openShiftClient.applyMany(adc.namespace, openShiftObjects, dryRun)

        return ApplicationResult(
                applicationId = ApplicationId(adc.envName, adc.name),
                auroraDc = adc,
                openShiftResponses = openShiftResponses
        )
    }
}