package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.newObjectMeta
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.findSubKeys
import no.skatteetaten.aurora.boober.model.openshift.AlertConfig
import no.skatteetaten.aurora.boober.model.openshift.AlertSpec
import no.skatteetaten.aurora.boober.model.openshift.Alerts
import no.skatteetaten.aurora.boober.model.openshift.ApplicationConfig
import no.skatteetaten.aurora.boober.model.openshift.PrometheusConfig
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.oneOf
import org.springframework.stereotype.Service

data class AlertConfiguration(
    val enabled: Boolean,
    val expr: String,
    val delay: String,
    val connection: String,
    val severity: String,
    val summary: String,
    val description: String
)

@Service
class AlertsFeature : Feature {

    enum class Errors(val message: String) {
        MissingAlertEnabledProp("alerts/<name>/enabled or alertsDefaults/enabled is required in alert configuration"),
        MissingAlertConnectionProp("alerts/<name>/connection or alertsDefaults/connection is required in alert configuration"),
        MissingAlertDelayProp("alerts/<name>/delay or alertsDefaults/delay is required in alert configuration"),
        MissingAlertExpression("alerts/<name>/expr is required in alert configuration"),
        MissingAlertSeverity("alerts/<name>/severity is required in alert configuration")
    }

    val defaultsName = "alertsDefaults"
    val featureName = "alerts"

    val alertsConfigKeys: List<String> = listOf(
        "enabled",
        "expr",
        "delay",
        "connection",
        "severity"
    )

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return !header.isJob
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        val alertsDefaults = setOf(
            AuroraConfigFieldHandler("$defaultsName/enabled"),
            AuroraConfigFieldHandler("$defaultsName/connection"),
            AuroraConfigFieldHandler("$defaultsName/delay"))
        val definedAlerts = cmd.applicationFiles.getDefinedAlerts()
            .flatMap { name ->
                setOf(
                    AuroraConfigFieldHandler("$featureName/$name/enabled", { it.boolean() }),
                    AuroraConfigFieldHandler("$featureName/$name/expr"),
                    AuroraConfigFieldHandler("$featureName/$name/delay"),
                    AuroraConfigFieldHandler("$featureName/$name/connection"),
                    AuroraConfigFieldHandler("$featureName/$name/severity", validator = { node ->
                        node?.oneOf(listOf("warning", "critical"))
                    }),
                    AuroraConfigFieldHandler("$featureName/$name/summary"),
                    AuroraConfigFieldHandler("$featureName/$name/description")
                )
            }.toSet()
        return definedAlerts + alertsDefaults
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {
        val alarms = adc.getDefinedAlerts()

        val isExprConfigMissing = alarms.any {
            adc.getOrNull<String>("$featureName/$it/expr").isNullOrEmpty()
        }

        val isEnabledPropertyMissing = alarms.any {
            adc.getOrNull<String>("$featureName/$it/enabled").isNullOrEmpty() &&
                    adc.getOrNull<String>("$defaultsName/enabled").isNullOrEmpty()
        }

        val isConnectionPropertyMissing = alarms.any {
            adc.getOrNull<String>("$featureName/$it/connection").isNullOrEmpty() &&
                    adc.getOrNull<String>("$defaultsName/connection").isNullOrEmpty()
        }

        val isDelayPropertyMissing = alarms.any {
            adc.getOrNull<String>("$featureName/$it/delay").isNullOrEmpty() &&
                    adc.getOrNull<String>("$defaultsName/delay").isNullOrEmpty()
        }

        val isSeverityMissing = alarms.any {
            adc.getOrNull<String>("$featureName/$it/severity").isNullOrEmpty()
        }

        val validationErrors = mutableListOf<AuroraDeploymentSpecValidationException>()
        if (isExprConfigMissing) {
            validationErrors.add(AuroraDeploymentSpecValidationException(Errors.MissingAlertExpression.message))
        }

        if (isEnabledPropertyMissing) {
            validationErrors.add(AuroraDeploymentSpecValidationException(Errors.MissingAlertEnabledProp.message))
        }

        if (isConnectionPropertyMissing) {
            validationErrors.add(AuroraDeploymentSpecValidationException(Errors.MissingAlertConnectionProp.message))
        }

        if (isDelayPropertyMissing) {
            validationErrors.add(AuroraDeploymentSpecValidationException(Errors.MissingAlertDelayProp.message))
        }

        if (isSeverityMissing) {
            validationErrors.add(AuroraDeploymentSpecValidationException(Errors.MissingAlertSeverity.message))
        }

        return validationErrors
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        return adc.getDefinedAlerts()
            .flatMap { generateAlertResources(it, adc) }
            .toSet()
    }

    private fun List<AuroraConfigFile>.getDefinedAlerts(): Set<String> {
        return this.findSubKeys(featureName).filter { !alertsConfigKeys.contains(it) }.toSet()
    }

    private fun AuroraDeploymentSpec.getDefinedAlerts(): Set<String> {
        return this.findSubKeysRaw(featureName)
            .toSet()
    }

    fun generateAlertResources(alertName: String, adc: AuroraDeploymentSpec): Set<AuroraResource> {
        val alertConfig = adc.extractConfiguration(alertName)

        if (!alertConfig.enabled) {
            return emptySet()
        }

        val alerts = Alerts(
            _metadata = newObjectMeta {
                name = alertName
                namespace = adc.namespace
            },
            spec = AlertSpec(
                ApplicationConfig(adc.affiliation, adc.cluster, adc.envName, adc.name),
                PrometheusConfig(alertConfig.expr),
                AlertConfig(
                    alertConfig.delay,
                    alertConfig.severity,
                    alertConfig.connection,
                    alertConfig.enabled,
                    alertConfig.summary,
                    alertConfig.description
                )
            )
        )

        return setOf(alerts.generateAuroraResource())
    }

    private fun AuroraDeploymentSpec.extractConfiguration(alertName: String): AlertConfiguration {
        val confPath = "$featureName/$alertName"

        val alertEnabled = this.getOrNull<Boolean>("$confPath/enabled")
        val alertExpr = this.getOrNull<String>("$confPath/expr")
            ?: throw IllegalStateException("Missing $confPath/expr value, check validation-logic")
        val alertDelay = this.getOrNull<String>("$confPath/delay")
        val alertConnection = this.getOrNull<String>("$confPath/connection")
        val alertSeverity = this.getOrNull<String>("$confPath/severity")
            ?: throw IllegalStateException("Missing $confPath/severity value, check validation-logic")
        val alertSummary = this.getOrNull<String>("$confPath/summary") ?: "oppsummering av alarm er ikke angitt"
        val alertDescription = this.getOrNull<String>("$confPath/description") ?: "beskrivelse av alarm er ikke angitt"

        val defaultEnabled = this.getOrNull<Boolean>("$defaultsName/enabled") ?: false
        val defaultDelay = this.getOrNull<String>("$defaultsName/delay") ?: "1"
        val connection = alertConnection ?: this.getOrNull<String>("$defaultsName/connection")
        ?: throw IllegalStateException("Missing $confPath/connection value, check validation-logic")

        return AlertConfiguration(
            enabled = alertEnabled ?: defaultEnabled,
            expr = alertExpr,
            delay = alertDelay ?: defaultDelay,
            connection = alertConnection ?: connection,
            severity = alertSeverity,
            summary = alertSummary,
            description = alertDescription
        )
    }
}
