package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.feature.affiliation
import no.skatteetaten.aurora.boober.feature.applicationDeploymentId
import no.skatteetaten.aurora.boober.feature.envName
import no.skatteetaten.aurora.boober.feature.name
import no.skatteetaten.aurora.boober.feature.releaseTo
import no.skatteetaten.aurora.boober.feature.version
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class NotificationService(
    val mattermostService: MattermostService,
    val userDetailsProvider: UserDetailsProvider,
    val auroraConfigService: AuroraConfigService,
    @Value("\${integrations.bitbucket.url}") val gitUrl: String,
    @Value("\${openshift.cluster}") val cluster: String,
    @Value("\${integrations.openshift.url}") val openshiftUrl: String,
    @Value("\${integrations.konsoll.url}") val konsollUrl: String
) {
    fun sendDeployNotifications(
        ref: AuroraConfigRef,
        deployResults: Map<String, List<AuroraDeployResult>>
    ): List<Exception> {
        val auroraConfig = auroraConfigService.findAuroraConfig(ref)

        return deployResults.values
            .flatten()
            .mapToListOfDeployResultAndNotification()
            .groupDeployResultsByNotification()
            .filterKeys {
                it.type == NotificationType.Mattermost
            }
            .sendMattermostNotification(auroraConfig)
    }

    private fun List<Pair<AuroraDeployResult, Notification>>.groupDeployResultsByNotification(): Map<Notification, List<AuroraDeployResult>> =
        this.groupBy(keySelector = { (_, notification) -> notification }) { (deployResult, _) ->
            deployResult
        }

    private fun Map<Notification, List<AuroraDeployResult>>.sendMattermostNotification(auroraConfig: AuroraConfig): List<Exception> {
        val user = userDetailsProvider.getAuthenticatedUser().username

        val headerMessage = """
             ##### Deploy has been initiated by @$user in cluster [$cluster]($openshiftUrl)
        """.trimIndent()

        return this.mapNotNull { (notification, deployResults) ->
            val attachments = deployResults.createMattermostAttachments(auroraConfig)

            mattermostService.sendMessage(
                notification.notificationLocation,
                headerMessage,
                attachments
            )
        }
    }

    private fun List<AuroraDeployResult>.createMattermostAttachments(auroraConfig: AuroraConfig): List<Attachment> {
        return this.map { result ->
            val adc = result.auroraDeploymentSpecInternal
            val applicationDeploymentSpec = result.findApplicationDeploymentSpec()

            val color = if (result.success) AttachmentColor.Green else AttachmentColor.Red
            val releaseToFieldIfPresent = adc.releaseTo?.let { AttachmentField(true, "ReleaseTo", it) }
            val errorIfPresent = if (!result.success) AttachmentField(
                false,
                "Errror message",
                result.reason ?: "Unknown errror caused deploy to fail"
            ) else null

            val konsollLink = "[info]($konsollUrl/a/${adc.affiliation}/deployments/${adc.applicationDeploymentId}/info)"
            val semanticVersion =
                if (adc.version != applicationDeploymentSpec.appVersion) "   =>   ${applicationDeploymentSpec.appVersion}" else ""
            Attachment(
                color = color.toString(),
                text = "#### ${adc.name} in env ${adc.envName}",
                fields = listOfNotNull(
                    AttachmentField(
                        true,
                        "Version",
                        "${adc.version}$semanticVersion"
                    ),
                    releaseToFieldIfPresent,
                    AttachmentField(
                        true,
                        "AuroraVersion",
                        applicationDeploymentSpec.auroraVersion ?: "Information not available"
                    ),
                    AttachmentField(true, "deployId", result.deployId),
                    AttachmentField(
                        true,
                        "AuroraConfig source",
                        getGitLink(auroraConfig, result.command.applicationDeploymentRef, adc)
                    ),
                    AttachmentField(true, "Link to Aurora Konsoll for app", konsollLink),
                    errorIfPresent
                ),
                authorName = "Courtesy of Aurora",
                authorIcon = "https://static.wikia.nocookie.net/muppet/images/d/da/Boober_Fraggle.jpg/revision/latest?cb=20121231172124",
                authorLink = "https://wiki.sits.no/display/AURORA/Aurora+Utviklingsplattform"

            )
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

    private fun getGitLink(
        auroraConfig: AuroraConfig,
        adRef: ApplicationDeploymentRef,
        adc: AuroraDeploymentSpec
    ): String {
        val configName = auroraConfig.getApplicationFile(adRef).name
        return "[${configName.substringAfterLast("/")}](${this.gitUrl}projects/AC/repos/${adc.affiliation}/browse/$configName)"
    }
}

enum class NotificationType {
    Email, Mattermost
}

data class Notification(val notificationLocation: String, val type: NotificationType)
