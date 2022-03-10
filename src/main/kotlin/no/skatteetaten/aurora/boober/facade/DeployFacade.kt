package no.skatteetaten.aurora.boober.facade

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.util.StopWatch
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.feature.cluster
import no.skatteetaten.aurora.boober.feature.namespace
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.model.InvalidDeploymentContext
import no.skatteetaten.aurora.boober.model.createDeployCommand
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.AuroraDeployResult
import no.skatteetaten.aurora.boober.service.AuroraDeploymentContextService
import no.skatteetaten.aurora.boober.service.AuroraEnvironmentResult
import no.skatteetaten.aurora.boober.service.ContextCreationErrors
import no.skatteetaten.aurora.boober.service.DeployLogService
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationResultException
import no.skatteetaten.aurora.boober.service.NotificationService
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftDeployer
import no.skatteetaten.aurora.boober.service.openshift.toEnvironmentSpec
import no.skatteetaten.aurora.boober.utils.parallelMap

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
    val notificationService: NotificationService,
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

        /* Vurdere å endre algoritmen her, den er nå
   0. Finn ut hvilke prosjekter som skal lages og for hver av dem gjør:
   1. Sjekk om prosjektet finnes, hvis det ikke finnes lag det.
   2. Oppdater namespace/rolebindings i prosjektet
   3. For hver applikasjon i prosjektet
   3.1 Hvis prosjektet ble laget kan vi bare lage alle ressursene siden det da ikke finnes noe her
   3.2 Hvis prosjektet ikke finnes må vi sjekke om ressursen finnes først. OpenShiftCommand og type blir brukt for å markere dette.
   3.3 Ressursen blir så laget/oppdatert
   4. Etter at vi er ferdig henter vi alle ressurser som ikke ble oppdatert i loopen over og sletter dem.

   Vurdere å endre til.
   1. Finn ut hvilke unike prosjektet so skal lages
   2. Hvis det finnes hent ned alle ressurser i prosjektet som tilhører miljøet. (namespace/rolebindings)
   3. Send disse eksisterende ressursene til prosedyren som lager/sletter navnerom. Hvis en rolebinding ikke finnes lengre
   slett den her, ikke på bunnen.
   4. For hver applikasjon i prosjektet hent først alle ressursene for det (gitt at prosjektet fantes)
   5. Send disse data inn til algoritmen som lager ressurser og la den lage/slette ressurser i samme prosedyren.
   6. Da ungår vi at vi må hente alle ressurser på bunnen og slette dem i en egen unik operasjon.
   7. Vi slipper også booberDeployId konseptet hvis vi ikke vil ha det lengre, vi har jo ownerReference idag som sier at
    noe tilhører en gitt deploy

    Vi har uansett problemer med å rulle tilbake alle endringer når en deploy feiler, men nå er det kanskje lettere å gjøre det?
    Hvis vi har en liste over alle ressurser for en applikasjon før vi starter å lage den så kan vi vurdere å legge dem tilbake hvis noe feiler?

    Kanskje kunne styre det med et flag.

 */

        watch.start("createNamespaces")
        val environmentResults = if (deploy) createNamespaces(validContexts) else emptyMap()
        watch.stop()

        watch.start("deployCommand")
        val deployCommands = validContexts.createDeployCommand(deploy)
        watch.stop()

        watch.start("deploy")
        val deployResults = openShiftDeployer.performDeployCommands(environmentResults, deployCommands)
        watch.stop()

        watch.start("send notification")
        val deployResultsAfterNotifications = notificationService.sendDeployNotifications(deployResults)
        watch.stop()

        watch.start("store result")
        return deployLogService.markRelease(deployResultsAfterNotifications).also {
            watch.stop()
            watch.taskInfo.forEach {
                logger.info("deployMs={} deployAction={}", it.timeMillis, it.taskName)
            }
        }
    }

    private fun createNamespaces(validContexts: List<AuroraDeploymentContext>): Map<String, AuroraEnvironmentResult> {

        val environmentSpecs = validContexts
            .map { it.spec }
            .groupBy { it.namespace }
            .map { (_, adcList) -> adcList.first().toEnvironmentSpec() }

        return environmentSpecs.associate { spec ->
            spec.namespace to openShiftDeployer.prepareDeployEnvironment(spec)
        }
    }

    private fun createContextCommands(
        ref: AuroraConfigRef,
        applicationDeploymentRefs: List<ApplicationDeploymentRef>,
        overrides: List<AuroraConfigFile>
    ): List<AuroraContextCommand> {
        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        // TODO: Do we really need this? Can we not fetch it from auroraConfig
        val auroraConfigRefExact = ref.copy(resolvedRef = auroraConfig.resolvedRef)

        return applicationDeploymentRefs.parallelMap {
            AuroraContextCommand(auroraConfig, it, auroraConfigRefExact, overrides)
        }
    }

    private fun createAuroraDeploymentContexts(commands: List<AuroraContextCommand>): List<AuroraDeploymentContext> {
        val (valid, invalid) = auroraDeploymentContextService.createValidatedAuroraDeploymentContexts(commands)

        if (invalid.isNotEmpty()) {
            throw MultiApplicationValidationResultException(valid, invalid, "Validation error in AuroraConfig")
        }

        validateUnusedOverrideFiles(valid)

        val (validContexts, invalidContexts) = valid.partition { it.spec.cluster == cluster }

        if (invalidContexts.isNotEmpty()) {
            val errors = invalidContexts.map {
                InvalidDeploymentContext(
                    it.cmd,
                    ContextCreationErrors(
                        it.cmd,
                        listOf(IllegalArgumentException("Not valid in this cluster"))
                    )
                )
            }
            val errorMessages = errors.flatMap { err ->
                err.errors.errors.map { it.localizedMessage }
            }

            logger.debug("Validation errors: ${errorMessages.joinToString("\n", prefix = "\n")}")

            throw MultiApplicationValidationResultException(validContexts, errors, "Invalid cluster configuration")
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
