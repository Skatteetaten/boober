package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.newObjectMeta
import com.fkorotkov.kubernetes.newOwnerReference
import io.fabric8.kubernetes.api.model.EnvVar
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.addEnvVarsToMainContainers
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentSpec
import no.skatteetaten.aurora.boober.model.openshift.Notifications
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.utils.Instants
import no.skatteetaten.aurora.boober.utils.addIfNotNull
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

val emailNotificationsField = "notification/email"
val mattermostNotificationsField = "notification/mattermost"

@Service
class ApplicationDeploymentFeature : Feature {

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler("message"),
            AuroraConfigFieldHandler("ttl", validator = { it.durationString() }),
            AuroraConfigFieldHandler(emailNotificationsField),
            AuroraConfigFieldHandler(mattermostNotificationsField)

        )
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        cmd: AuroraContextCommand
    ): List<Exception> {
        return adc.getDelimitedStringOrArrayAsSet(emailNotificationsField, " ").mapNotNull { email ->
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

    private fun AuroraDeploymentSpec.findNotifications(): Notifications? {
        val mattermost = this.getDelimitedStringOrArrayAsSetOrNull(mattermostNotificationsField, " ")
        val email = this.getDelimitedStringOrArrayAsSetOrNull(emailNotificationsField, " ")

        if (mattermost == null && email == null) {
            return null
        }

        return Notifications(mattermost = mattermost, email = email)
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
