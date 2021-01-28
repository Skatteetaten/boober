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
import no.skatteetaten.aurora.boober.service.NotificationService
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
    val notificationService: NotificationService,
    @Value("\${openshift.cluster}") val cluster: String
) {

    /*
    OVERFORING
        Boober skiller ikke på deploy av env og deploy av applikasjon idag, det tror jeg var dumt
        Man kunne laget config for app og env separat, og ao/gobo kunne vært smart og laget det hvis det manglet første gang
        Da ville det kunne vært mye klarere hva som er config på env nivå og hva som er config på app nivå.
        Noe config vil muligens være delt mellom alle apper i et env dog.

     */
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

        // OVERFORING Vi må hente context for alle før vi deployer noe siden noen warnings kjøres på kryss av alle contexter
        watch.start("ADC")
        val validContexts = createAuroraDeploymentContexts(commands)
        watch.stop()

        //OVERFORING kommentareren under her er fremdeles relevant

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
        watch.start("deployCommand")
        //OVERFORING: Her kjøres alle featurene og lager ressurser
        // TODO: rename this method and the variable?
        val deployCommands = validContexts.createDeployCommand(deploy)
        watch.stop()

        watch.start("deploy")
        val deployResults = openShiftDeployer.performDeployCommands(deployCommands)
        watch.stop()

        watch.start("send notification")
        val deployResultsAfterNotifications = notificationService.sendDeployNotifications(deployResults)
        watch.stop()

        watch.start("store result")
        val deployer = userDetailsProvider.getAuthenticatedUser().let {
            Deployer(it.fullName ?: it.username, "${it.username}@skatteetaten.no")
        }
        return deployLogService.markRelease(deployResultsAfterNotifications, deployer).also {
            watch.stop()
            logger.info("Deploy: ${watch.prettyPrint()}")
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
