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
            .createNotificationsForDeployResults()
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

    private fun List<AuroraDeployResult>.asBulletlistOfDeploys(isSuccessful: Boolean): String {
        return this.joinToString(separator = "\n") { deployResult ->
            val adSpec = deployResult.findApplicationDeploymentSpec()
            val message = if (deployResult.success) adSpec.message else deployResult.reason ?: "Unknown error"
            val adc = deployResult.auroraDeploymentSpecInternal

            "* **${adc.envName}/${adc.name}** *version*=${adc.version}  ${if (!isSuccessful) "*deployId*=${deployResult.deployId}  " else ""}      ${message?.let { "*message*=$it" } ?: ""}"
        }
    }

    private fun Map<Notification, List<AuroraDeployResult>>.sendMattermostNotification(): List<AuroraDeployResult> {
        val user = userDetailsProvider.getAuthenticatedUser().username

        val headerMessage = "@$user has deployed to cluster [$cluster]($openshiftUrl)"

        return this.flatMap { (notification, deployResults) ->
            val attachments = deployResults.createMattermostMessage()
            val exceptionOrNull = mattermostService.sendMessage(
                channelId = notification.notificationLocation,
                message = headerMessage,
                attachments = attachments
            )

            handleMattermostException(exceptionOrNull, deployResults, notification.notificationLocation)
        }.distinctBy { it.deployId }
    }

    private fun List<AuroraDeployResult>.createDeployResultMessage(isSuccessful: Boolean): Attachment? {
        if (this.isEmpty()) return null
        val listOfDeploys = this.asBulletlistOfDeploys(isSuccessful = isSuccessful)

        val headerMessage =
            if (isSuccessful) "Successful deploys" else "Failed deploys \n For more information run `ao inspect <deployId>` in cli"
        val color = if (isSuccessful) AttachmentColor.Green else AttachmentColor.Red

        val text = """
                |#### $headerMessage
                |$listOfDeploys
        """.trimMargin()

        return Attachment(
            color = color.hex,
            text = text
        )
    }

    private fun List<AuroraDeployResult>.createMattermostMessage(): List<Attachment> {
        val listOfSuccessDeploys = this.filter { it.success }.createDeployResultMessage(isSuccessful = true)
        val listOfFailedDeploys = this.filter { !it.success }.createDeployResultMessage(isSuccessful = false)

        return listOfNotNull(
            listOfSuccessDeploys,
            listOfFailedDeploys
        )
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

    private fun List<AuroraDeployResult>.createNotificationsForDeployResults(): List<Pair<Notification, AuroraDeployResult>> =
        this.flatMap { result ->
            val notifications = result.findApplicationDeploymentSpec().notifications ?: emptySet()

            notifications.map {
                it to result
            }
        }
}
