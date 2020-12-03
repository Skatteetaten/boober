package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.feature.envName
import no.skatteetaten.aurora.boober.feature.name
import no.skatteetaten.aurora.boober.feature.version
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.model.openshift.Notification
import no.skatteetaten.aurora.boober.model.openshift.NotificationType
import no.skatteetaten.aurora.boober.utils.findResourceByType
import no.skatteetaten.aurora.boober.utils.toMultiMap
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class NotificationService(
    val mattermostService: MattermostService,
    val userDetailsProvider: UserDetailsProvider,
    @Value("\${openshift.cluster}") val cluster: String,
    @Value("\${integrations.openshift.url}") val openshiftUrl: String
) {
    fun sendDeployNotifications(
        deployResults: List<AuroraDeployResult>
    ): List<AuroraDeployResult> {
        val deployResultsWithNotifications = deployResults
            .mapToListOfNotificationAndDeployResult()
            .toMultiMap()
            .filterKeys {
                it.type == NotificationType.Mattermost
            }
            .sendMattermostNotification()

        val deployResultWithoutNotifications = deployResults
            .filter {
                val notifications = it.findApplicationDeploymentSpec().notifications ?: emptySet()

                notifications.isEmpty()
            }

        return deployResultWithoutNotifications + deployResultsWithNotifications
    }


    private fun List<AuroraDeployResult>.asListOfDeploysWithVersion() =
        this.joinToString(separator = "\n") { deployResult ->
            val adSpec = deployResult.findApplicationDeploymentSpec()
            val message = if (deployResult.success) adSpec.message ?: "" else deployResult.reason ?: "Unknown error"
            val adc = deployResult.auroraDeploymentSpecInternal

            "* ${adc.envName}/${adc.name}   -   ${adc.version}  $message"
        }

    private fun Map<Notification, List<AuroraDeployResult>>.sendMattermostNotification(): List<AuroraDeployResult> {
        val user = userDetailsProvider.getAuthenticatedUser().username

        val headerMessage = "##### @$user has deployed in cluster [$cluster]($openshiftUrl)"

        return this.flatMap { (notification, deployResults) ->
            val message = createMattermostMessage(deployResults, headerMessage)
            val exceptionOrNull = mattermostService.sendMessage(
                channelId = notification.notificationLocation,
                message = message
            )

            handleMattermostException(exceptionOrNull, deployResults, notification.notificationLocation)
        }.distinctBy { it.deployId }
    }

    private fun createMattermostMessage(
        deployResults: List<AuroraDeployResult>,
        headerMessage: String
    ): String {
        val listOfSuccessDeploys = deployResults.filter { it.success }.asListOfDeploysWithVersion()
        val listOfFailedDeploys = deployResults.filterNot { it.success }.asListOfDeploysWithVersion()
        val failedDeploysText = if (listOfFailedDeploys.isNotEmpty()) {
            """
                       |
                       |
                       |##### Some applications could not be deployed
                       |$listOfFailedDeploys
                    """.trimMargin()
        } else ""

        return """
            |$headerMessage
            |
            |$listOfSuccessDeploys
            |$failedDeploysText
        """.trimMargin()
    }

    private fun handleMattermostException(
        exceptionOrNull: Exception?,
        deployResults: List<AuroraDeployResult>,
        notificationLocation: String
    ) = if (exceptionOrNull != null) {
        deployResults.map {
            it.copy(reason = it.reason + "Failed to send notification to mattermost channel_id=$notificationLocation")
        }
    } else {
        deployResults
    }

    private fun AuroraDeployResult.findApplicationDeploymentSpec() = this.deployCommand
        .resources
        .findResourceByType<ApplicationDeployment>()
        .spec

    private fun List<AuroraDeployResult>.mapToListOfNotificationAndDeployResult(): List<Pair<Notification, AuroraDeployResult>> =
        this.flatMap { result ->
            val notifications = result.findApplicationDeploymentSpec().notifications ?: emptySet()

            notifications.map {
                it to result
            }
        }
}
