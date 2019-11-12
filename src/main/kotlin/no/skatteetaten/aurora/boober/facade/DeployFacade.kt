package no.skatteetaten.aurora.boober.facade

import mu.KotlinLogging
import no.skatteetaten.aurora.boober.feature.cluster
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.model.createDeployCommand
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.AuroraDeployResult
import no.skatteetaten.aurora.boober.service.AuroraDeploymentContextService
import no.skatteetaten.aurora.boober.service.ContextErrors
import no.skatteetaten.aurora.boober.service.DeployLogService
import no.skatteetaten.aurora.boober.service.Deployer
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftDeployer
import no.skatteetaten.aurora.boober.utils.parallelMap
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.util.StopWatch

private val logger = KotlinLogging.logger {}

// TODO: validere at serivceAccount til DC finnes?
// TODo test errors
@Service
class DeployFacade(
    val auroraConfigService: AuroraConfigService,
    val auroraDeploymentContextService: AuroraDeploymentContextService,
    val openShiftDeployer: OpenShiftDeployer,
    val userDetailsProvider: UserDetailsProvider,
    val deployLogService: DeployLogService,
    @Value("\${openshift.cluster}") val cluster: String
) {

    fun executeDeploy(
        ref: AuroraConfigRef,
        applicationDeploymentRefs: List<ApplicationDeploymentRef>,
        overrides: List<AuroraConfigFile> = listOf(),
        deploy: Boolean = true
    ): List<AuroraDeployResult> {

        // to controller
        if (applicationDeploymentRefs.isEmpty()) {
            throw IllegalArgumentException("Specify applicationDeploymentRef")
        }
        val watch = StopWatch("deploy")

        watch.start("contextCommand")
        val commands = createContextCommands(ref, applicationDeploymentRefs, overrides)
        watch.stop()

        watch.start("ADC")
        val validContexts = createAuroraDeploymentContexts(commands)
        watch.stop()

        watch.start("deployCommand")
        val deployCommands = validContexts.createDeployCommand(deploy)
        watch.stop()

        watch.start("deploy")
        val deployResults = openShiftDeployer.performDeployCommands(deployCommands)
        watch.stop()

        watch.start("store result")
        val deployer = userDetailsProvider.getAuthenticatedUser().let {
            Deployer(it.fullName ?: it.username, "${it.username}@skatteetaten.no")
        }
        return deployLogService.markRelease(deployResults.flatMap { it.value }, deployer).also {
            watch.stop()
            logger.info("Deploy: ${watch.prettyPrint()}")
        }
    }

    private fun createContextCommands(
        ref: AuroraConfigRef,
        applicationDeploymentRefs: List<ApplicationDeploymentRef>,
        overrides: List<AuroraConfigFile>
    ): List<AuroraContextCommand> {
        val auroraConfigRefExact = auroraConfigService.resolveToExactRef(ref)
        val auroraConfig = auroraConfigService.findAuroraConfig(auroraConfigRefExact)

        return applicationDeploymentRefs.parallelMap {
            AuroraContextCommand(auroraConfig, it, auroraConfigRefExact, overrides)
        }
    }

    private fun createAuroraDeploymentContexts(commands: List<AuroraContextCommand>): List<AuroraDeploymentContext> {
        val deploymentCtx = auroraDeploymentContextService.createValidatedAuroraDeploymentContexts(commands)
        validateUnusedOverrideFiles(deploymentCtx)

        val (validContexts, invalidContexts) = deploymentCtx.partition { it.spec.cluster == cluster }

        if (invalidContexts.isNotEmpty()) {
            val errors = invalidContexts.map {
                ContextErrors(
                    it.cmd,
                    listOf(java.lang.IllegalArgumentException("Not valid in this cluster"))
                )
            }

            val errorMessages = errors.flatMap { err ->
                err.errors.map { it.localizedMessage }
            }
            logger.debug("Validation errors: ${errorMessages.joinToString("\n", prefix = "\n")}")

            throw MultiApplicationValidationException(errors)
        }
        return validContexts
    }

    private fun validateUnusedOverrideFiles(deploymentCtx: List<AuroraDeploymentContext>) {
        val overrides = deploymentCtx.first().cmd.overrides
        val usedOverrideNames: List<String> =
            deploymentCtx.flatMap { ctx -> ctx.cmd.applicationFiles.filter { it.override } }.map { it.configName }

        val applicationDeploymentRefs = deploymentCtx.map { it.cmd.applicationDeploymentRef }
        val unusedOverrides = overrides.filter { !usedOverrideNames.contains(it.configName) }
        if (unusedOverrides.isNotEmpty()) {
            val overrideString = unusedOverrides.joinToString(",") { it.name }
            val refString = applicationDeploymentRefs.joinToString(",")
            throw IllegalArgumentException(
                "Overrides files '$overrideString' does not apply to any deploymentReference ($refString)"
            )
        }
    }
}
