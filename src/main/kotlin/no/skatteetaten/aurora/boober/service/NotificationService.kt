package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.feature.envName
import no.skatteetaten.aurora.boober.feature.name
import no.skatteetaten.aurora.boober.feature.version
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
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
            .mapToListOfDeployResultAndNotification()
            .groupDeployResultsByNotification()
            .filterKeys {
                it.type == NotificationType.Mattermost
            }
            .sendMattermostNotification()

        val deployResultWithoutNotifications = deployResults
            .filter {
                val notifications = it.findApplicationDeploymentSpec().notifications
                val email = notifications?.email?.map { Notification(it, NotificationType.Email) } ?: emptyList()
                val mattermost =
                    notifications?.mattermost?.map { Notification(it, NotificationType.Mattermost) } ?: emptyList()
                email.isEmpty() && mattermost.isEmpty()
            }
        return deployResultWithoutNotifications + deployResultsWithNotifications
    }

    private fun List<Pair<AuroraDeployResult, Notification>>.groupDeployResultsByNotification(): Map<Notification, List<AuroraDeployResult>> =
        this.groupBy(keySelector = { (_, notification) -> notification }) { (deployResult, _) ->
            deployResult
        }

    private fun List<AuroraDeployResult>.asListOfDeploysWithVersion() = this.joinToString(separator = "\n") {
        val adSpec = it.findApplicationDeploymentSpec()
        val message = adSpec.message ?: ""
        val adc = it.auroraDeploymentSpecInternal

        "* ${adc.envName}/${adc.name}   -   ${adc.version}  $message"
    }

    private fun Map<Notification, List<AuroraDeployResult>>.sendMattermostNotification(): List<AuroraDeployResult> {
        val user = userDetailsProvider.getAuthenticatedUser().username

        val headerMessage = """
             ##### Deploy has been initiated by @$user in cluster [$cluster]($openshiftUrl)
        """.trimIndent()

        return this.flatMap { (notification, deployResults) ->
            val listOfDeploys = deployResults.asListOfDeploysWithVersion()

            val exception = mattermostService.sendMessage(
                channelId = notification.notificationLocation,
                message = """
                        $headerMessage
                        
                        $listOfDeploys
                    """.trimIndent()
            )

            if (exception != null) {
                deployResults.map {
                    it.copy(warnings = it.warnings + listOf("Failed to send notification to mattermost channel_id=${notification.notificationLocation}"))
                }
            } else {
                deployResults
            }
        }.groupBy {
            it.deployId
        }.map { (deployId, deployResults) ->
            val warnings = deployResults.map { it.warnings }.flatten().distinct()
            deployResults.first().copy(warnings = warnings)
        }
    }

    private fun AuroraDeployResult.findApplicationDeploymentSpec() = this.deployCommand
        .resources
        .map { it.resource }
        .find { it is ApplicationDeployment }
        .let { it as ApplicationDeployment }
        .spec

    private fun List<AuroraDeployResult>.mapToListOfDeployResultAndNotification(): List<Pair<AuroraDeployResult, Notification>> =
        this.flatMap { result ->
            val notifications = result.findApplicationDeploymentSpec().notifications

            val email = notifications?.email?.map { Notification(it, NotificationType.Email) } ?: emptyList()
            val mattermost =
                notifications?.mattermost?.map { Notification(it, NotificationType.Mattermost) } ?: emptyList()
            val notificationsWithType = email + mattermost

            notificationsWithType.map {
                result to it
            }
        }
}

enum class NotificationType {
    Email, Mattermost
}

data class Notification(val notificationLocation: String, val type: NotificationType)
