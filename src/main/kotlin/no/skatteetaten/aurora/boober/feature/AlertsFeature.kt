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
    val connections: List<String>,
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

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        val alertsDefaults = setOf(
            AuroraConfigFieldHandler("$defaultsName/enabled"),
            AuroraConfigFieldHandler("$defaultsName/connection"),
            AuroraConfigFieldHandler("$defaultsName/connections"),
            AuroraConfigFieldHandler("$defaultsName/delay")
        )
        val definedAlerts = cmd.applicationFiles.getDefinedAlerts()
            .flatMap { name ->
                setOf(
                    AuroraConfigFieldHandler("$featureName/$name/enabled", { it.boolean() }),
                    AuroraConfigFieldHandler("$featureName/$name/expr"),
                    AuroraConfigFieldHandler("$featureName/$name/delay"),
                    AuroraConfigFieldHandler("$featureName/$name/connection"),
                    AuroraConfigFieldHandler("$featureName/$name/connections"),
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

        val isConnectionsPropertyMissing = alarms.any {
            adc.getOrNull<List<String>>("$featureName/$it/connections").isNullOrEmpty() &&
                adc.getOrNull<List<String>>("$defaultsName/connections").isNullOrEmpty()
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

        if (isConnectionPropertyMissing && isConnectionsPropertyMissing) {
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
                name = "${adc.name}-$alertName"
                namespace = adc.namespace
            },
            spec = AlertSpec(
                ApplicationConfig(adc.affiliation, adc.cluster, adc.envName, adc.name),
                PrometheusConfig(alertConfig.expr),
                AlertConfig(
                    alertConfig.delay,
                    alertConfig.severity,
                    alertConfig.connections,
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

        // non-nullable w. no-default
        val confExpression = this.get<String>("$confPath/expr")
        val confSeverity = this.get<String>("$confPath/severity")

        // nullable w. conf-default + fallback
        val confEnabled = this.getWithDefaultFallback(confPath, "enabled", false, defaultsName)
        val confDelay = this.getWithDefaultFallback(confPath, "delay", "1", defaultsName)

        // nullable w. fallback
        val confSummary = this.getWithDefaultFallback(confPath, "summary", "oppsummering av alarm er ikke angitt")
        val confDescription = this.getWithDefaultFallback(confPath, "description", "beskrivelse av alarm er ikke angitt")

        // connection rules handling
        val confConnections = this.getAlertConnectionRules(confPath)

        return AlertConfiguration(
            enabled = confEnabled,
            expr = confExpression,
            delay = confDelay,
            connections = confConnections,
            severity = confSeverity,
            summary = confSummary,
            description = confDescription
        )
    }

    private inline fun <reified T> AuroraDeploymentSpec.getWithDefaultFallback(
        root: String,
        key: String,
        fallback: T,
        defaultRoot: String? = null
    ): T {
        val v = this.getOrNull<T>("$root/$key")
        val d = if (defaultRoot != null) {
            this.getOrNull<T>("$defaultRoot/$key")
        } else {
            null
        }
        return v ?: d ?: fallback
    }

    private fun AuroraDeploymentSpec.getAlertConnectionRules(confPath: String): List<String> {
        val alertConnection = this.getOrNull<String>("$confPath/connection")
        val defaultsConnection = this.getOrNull<String>("$defaultsName/connection")
        val implConnection = alertConnection ?: defaultsConnection

        val alertsConnections = this.getOrNull<List<String>>("$confPath/connections")
        val defaultsConnections = this.getOrNull<List<String>>("$defaultsName/connections")
        val implConnections = alertsConnections ?: defaultsConnections

        val list = mutableListOf<String>()

        if (!implConnection.isNullOrEmpty()) {
            list.add(implConnection)
        } else if (!implConnections.isNullOrEmpty()) {
            list.addAll(implConnections)
        } else {
            throw IllegalStateException("one of $confPath/connection and $confPath/connections must be defined")
        }

        return list
    }
}
