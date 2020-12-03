package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.newObjectMeta
import com.fkorotkov.kubernetes.newOwnerReference
import io.fabric8.kubernetes.api.model.EnvVar
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.addEnvVarsToMainContainers
import no.skatteetaten.aurora.boober.model.findSubKeys
import no.skatteetaten.aurora.boober.model.findSubKeysExpanded
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentSpec
import no.skatteetaten.aurora.boober.model.openshift.Notification
import no.skatteetaten.aurora.boober.model.openshift.NotificationType
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.utils.Instants
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.durationString
import no.skatteetaten.aurora.boober.utils.normalizeLabels
import org.springframework.boot.convert.DurationStyle.SIMPLE
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.regex.Pattern
import java.util.regex.Pattern.compile

val AuroraDeploymentSpec.ttl: Duration? get() = this.getOrNull<String>("ttl")?.let { SIMPLE.parse(it) }

val emailRegex: Pattern = compile(
    "[a-zA-Z0-9\\+\\.\\_\\%\\-\\+]{1,256}" +
        "\\@" +
        "[a-zA-Z0-9][a-zA-Z0-9\\-]{0,64}" +
        "(" +
        "\\." +
        "[a-zA-Z0-9][a-zA-Z0-9\\-]{0,25}" +
        ")+"
)

const val emailNotificationsField = "notification/email"
const val mattermostNotificationsField = "notification/mattermost"

@Service
class ApplicationDeploymentFeature : Feature {

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler("message"),
            AuroraConfigFieldHandler("ttl", validator = { it.durationString() })
        ) + findAllNotificationHandlers(cmd.applicationFiles)
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        cmd: AuroraContextCommand
    ): List<Exception> {
        return adc.getSubKeyValues(emailNotificationsField).mapNotNull { email ->
            if (!emailRegex.matcher(email).matches()) {
                AuroraDeploymentSpecValidationException("Email address '$email' is not a valid email address.")
            } else null
        }
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {

        val ttl = adc.ttl?.let {
            val removeInstant = Instants.now + it
            "removeAfter" to removeInstant.epochSecond.toString()
        }

        val resource = ApplicationDeployment(
            spec = ApplicationDeploymentSpec(
                selector = mapOf("name" to adc.name),
                updatedAt = Instants.now.toString(),
                message = adc.getOrNull("message"),
                applicationDeploymentName = adc.name,
                applicationDeploymentId = adc.applicationDeploymentId,
                command = ApplicationDeploymentCommand(
                    cmd.overrideFiles,
                    cmd.applicationDeploymentRef,
                    cmd.auroraConfigRef
                ),
                notifications = adc.findNotifications()
            ),
            _metadata = newObjectMeta {
                name = adc.name
                namespace = adc.namespace
                labels = mapOf("id" to adc.applicationDeploymentId).addIfNotNull(ttl).normalizeLabels()
            }
        )
        return setOf(generateResource(resource))
    }

    private fun AuroraDeploymentSpec.isNotificationLocationEnabled(notificationLocationField: String) =
        this.getOrNull<Boolean>(notificationLocationField) ?: this["$notificationLocationField/enabled"]

    private fun findAllNotificationHandlers(applicationFiles: List<AuroraConfigFile>): Set<AuroraConfigFieldHandler> {
        val mattermostHandlers = applicationFiles.findNotificationHandlers(mattermostNotificationsField)
        val emailHandlers = applicationFiles.findNotificationHandlers(emailNotificationsField)

        return emailHandlers + mattermostHandlers
    }

    private fun List<AuroraConfigFile>.findNotificationHandlers(notificationField: String) =
        this.findSubKeysExpanded(notificationField).flatMap { this.findNotificationHandler(it) }.toSet()

    private fun List<AuroraConfigFile>.findNotificationHandler(
        notificationLocationKey: String
    ): List<AuroraConfigFieldHandler> {
        val expandedMattermostKeys = this.findSubKeys(notificationLocationKey)
        return if (expandedMattermostKeys.isEmpty()) listOf(
            AuroraConfigFieldHandler(
                notificationLocationKey,
                validator = { it.boolean() }
            )
        )
        else listOf(
            AuroraConfigFieldHandler(
                "$notificationLocationKey/enabled",
                validator = { it.boolean() },
                defaultValue = true
            )
        )
    }

    private fun AuroraDeploymentSpec.findNotificationsByType(
        field: String,
        type: NotificationType
    ): List<Notification> =
        this.getSubKeyValues(field).filter {
            isNotificationLocationEnabled(notificationLocationField = "$field/$it")
        }.map {
            Notification(it, type)
        }

    private fun AuroraDeploymentSpec.findNotifications(): Set<Notification>? {
        val notifications =
            this.findNotificationsByType(emailNotificationsField, NotificationType.Email) +
                this.findNotificationsByType(mattermostNotificationsField, NotificationType.Mattermost)

        if (notifications.isEmpty()) {
            return null
        }

        return notifications.toSet()
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {

        resources.addEnvVarsToMainContainers(
            listOf(
                EnvVar("APPLICATION_DEPLOYMENT_ID", adc.applicationDeploymentId, null)
            ), this::class.java
        )
        resources.forEach {
            if (it.resource.metadata.namespace != null && it.resource.kind !in listOf(
                    "ApplicationDeployment",
                    "RoleBinding"
                )
            ) {
                modifyResource(it, "Set owner reference to ApplicationDeployment")
                it.resource.metadata.ownerReferences = listOf(
                    newOwnerReference {
                        apiVersion = "skatteetaten.no/v1"
                        kind = "ApplicationDeployment"
                        name = adc.name
                        uid = "123-123"
                    }
                )
            }
        }
    }
}
