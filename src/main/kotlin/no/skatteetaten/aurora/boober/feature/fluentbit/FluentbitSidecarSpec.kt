package no.skatteetaten.aurora.boober.feature.fluentbit

import org.apache.commons.codec.binary.Base64
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newConfigMap
import com.fkorotkov.kubernetes.newContainer
import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.kubernetes.newSecret
import com.fkorotkov.kubernetes.newVolumeMount
import com.fkorotkov.kubernetes.resources
import com.fkorotkov.kubernetes.secretKeyRef
import com.fkorotkov.kubernetes.valueFrom
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.Secret
import no.skatteetaten.aurora.boober.feature.TemplateType
import no.skatteetaten.aurora.boober.feature.cluster
import no.skatteetaten.aurora.boober.feature.loggingMount
import no.skatteetaten.aurora.boober.feature.name
import no.skatteetaten.aurora.boober.feature.namespace
import no.skatteetaten.aurora.boober.feature.podEnvVariables
import no.skatteetaten.aurora.boober.feature.type
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.utils.addIfNotNull

data class LoggingConfig(
    val name: String,
    val sourceType: String,
    val index: String,
    val filePattern: String,
    val excludePattern: String
)

fun AuroraDeploymentSpec.validateFluentbit(): List<Exception> {
    if (isFluentbitDisabled()) return emptyList()
    val onlyOneOfLoggerTypes = validateOnlyOneOfCustomOrStandardLogger()
    val validateRequiredLoggersForCustomIfPresent = validateRequiredLoggersForCustom()

    return listOfNotNull(onlyOneOfLoggerTypes, validateRequiredLoggersForCustomIfPresent)
}

fun AuroraDeploymentSpec.validateRequiredLoggersForCustom(): IllegalArgumentException? {
    val customLoggerNames = this.getSubKeyValues("$FEATURE_FIELD_NAME/custom")

    if (customLoggerNames.isEmpty()) return null

    if (!customLoggerNames.containsAll(listOf("application", "fluentbit"))) {
        return IllegalArgumentException("When using custom logger both fluentbit and application loggers are required")
    }

    return null
}

fun AuroraDeploymentSpec.validateOnlyOneOfCustomOrStandardLogger(): IllegalArgumentException? {
    val isCustomConfigured = this.getSubKeyValues("$FEATURE_FIELD_NAME/custom").isNotEmpty()
    val isLoggingConfigured = !this.loggingIndex.isNullOrEmpty()

    if (isCustomConfigured && isLoggingConfigured) {
        return IllegalArgumentException("Cannot use both custom loggers and the default loggers. If you wish to use custom loggers, then remove index and loggers")
    }

    return null
}

val AuroraDeploymentSpec.configuredLoggers
    get(): List<LoggingConfig> {
        val configuredCustomLoggerFields = this.getSubKeyValues("$FEATURE_FIELD_NAME/custom")
        val defaultIndex = this.loggingIndex

        return when {
            configuredCustomLoggerFields.isNotEmpty() -> this.getCustomLoggerConfig(configuredCustomLoggerFields)
            defaultIndex != null -> this.getLoggingIndexes(defaultIndex)
            else -> emptyList()
        }
    }

fun AuroraDeploymentSpec.getLoggingIndexes(defaultIndex: String): List<LoggingConfig> {
    val loggers = this.getLoggingIndexNames(defaultIndex)

    val fluentbitLogger = LoggingConfig(
        name = "fluentbit",
        sourceType = "fluentbit",
        index = defaultIndex,
        filePattern = "fluentbit",
        excludePattern = getLogRotationExcludePattern("fluentbit")
    )

    return loggers.map { (logType, index) ->
        val filePattern = getKnownFilePattern(logType)
        val sourceType = getKnownSourceType(logType)
        LoggingConfig(
            name = logType,
            sourceType = sourceType,
            index = index,
            filePattern = filePattern,
            excludePattern = getLogRotationExcludePattern(filePattern)
        )
    } + listOf(fluentbitLogger)
}

private fun AuroraDeploymentSpec.getLoggingIndexNames(
    defaultIndex: String
): Map<String, String> {

    val loggers: MutableMap<String, String> =
        this.associateSubKeys<String>("logging/loggers").filter {
            it.value != "false"
        }.toMutableMap()
    if (!loggers.containsKey(logApplication)) {
        loggers[logApplication] = defaultIndex
    }

    if (!loggers.containsKey(logAccess)) {
        loggers[logAccess] = defaultIndex
    }
    return loggers.toMap()
}

fun AuroraDeploymentSpec.isFluentbitDisabled(): Boolean {
    val isNotcustomConfig = this.getSubKeys("$FEATURE_FIELD_NAME/custom").isEmpty()
    val loggingIndexNotSet = this.loggingIndex.isNullOrEmpty()

    return isNotcustomConfig && loggingIndexNotSet
}

fun getKnownSourceType(logType: String): String {
    return when (logType) {
        logAuditJson -> "_json"
        logAccess -> "access_combined"
        logGC -> "gc_log"
        else -> "log4j"
    }
}

fun getKnownFilePattern(logger: String): String {
    return when (logger) {
        logApplication -> "*.log"
        logAuditText -> "*.audit.text"
        logAuditJson -> "*.audit.json"
        logAccess -> "*.access"
        logSlow -> "*.slow"
        logGC -> "*.gc"
        logSensitive -> "*.sensitive"
        logStacktrace -> "*.stacktrace"
        else -> "*.log"
    }
}

fun getLogRotationExcludePattern(logFilePattern: String): String {
    return logFilePattern.replace("*", "*.[1-9]")
}

fun AuroraDeploymentSpec.createFluentbitConfigMap(
    allConfiguredLoggers: List<LoggingConfig>,
    retryLimit: Int?
): ConfigMap {
    val adc = this
    return newConfigMap {
        metadata {
            name = adc.fluentConfigName
            namespace = adc.namespace
        }
        data = mapOf(
            "fluent-bit.conf" to FluentbitConfigurator.generateFluentBitConfig(
                allConfiguredLoggers = allConfiguredLoggers,
                application = adc.name,
                cluster = adc.cluster,
                bufferSize = adc.bufferSize,
                retryLimit = retryLimit
            )
        )
    }
}

fun AuroraDeploymentSpec.getCustomLoggerConfig(configuredCustomLoggerFields: List<String>): List<LoggingConfig> {
    return configuredCustomLoggerFields.map {
        val index: String = this["$FEATURE_FIELD_NAME/custom/$it/index"]
        val pattern: String = this["$FEATURE_FIELD_NAME/custom/$it/pattern"]
        val sourceType: String = this["$FEATURE_FIELD_NAME/custom/$it/sourcetype"]

        LoggingConfig(
            name = it,
            sourceType = sourceType,
            index = index,
            filePattern = pattern,
            excludePattern = getLogRotationExcludePattern(pattern)
        )
    }
}

fun AuroraDeploymentSpec.createFluentbitContainer(imagePath: String): Container {
    val hecSecretName = getHecSecretName(this.name)
    val hecEnvVariables = listOf(
        newEnvVar {
            name = hecTokenKey
            valueFrom {
                secretKeyRef {
                    key = hecTokenKey
                    name = hecSecretName
                    optional = false
                }
            }
        },
        newEnvVar {
            name = splunkHostKey
            valueFrom {
                secretKeyRef {
                    key = splunkHostKey
                    name = hecSecretName
                    optional = false
                }
            }
        },
        newEnvVar {
            name = splunkPortKey
            valueFrom {
                secretKeyRef {
                    key = splunkPortKey
                    name = hecSecretName
                    optional = false
                }
            }
        }
    )
    val adc = this

    return newContainer {
        name = adc.fluentSideCarContainerName
        env = podEnvVariables.addIfNotNull(hecEnvVariables)
        volumeMounts = listOf(
            newVolumeMount {
                name = adc.fluentParserName
                mountPath = parserMountPath
            },
            newVolumeMount {
                name = adc.fluentConfigName
                mountPath = "/fluent-bit/etc"
            },
            loggingMount
        )
        resources {
            limits = mapOf(
                // TODO? Add as config parameter
                "memory" to Quantity("${adc.bufferSize * 5}Mi"),
                "cpu" to Quantity("300m")
            )
            requests = mapOf(
                "memory" to Quantity("${adc.bufferSize}Mi"),
                "cpu" to Quantity("10m")
            )
        }
        image = imagePath
    }
}

fun AuroraDeploymentSpec.createFluentbitParserConfigmap(): ConfigMap {
    val adc = this
    val parserConfig = FluentbitConfigurator.parserConf()

    val fluentParserMap = newConfigMap {
        metadata {
            name = adc.fluentParserName
            namespace = adc.namespace
        }
        data = mapOf(parsersFileName to parserConfig)
    }
    return fluentParserMap
}

fun AuroraDeploymentSpec.createHecSecret(hecToken: String, splunkUrl: String, splunkPort: String): Secret {
    this.let {
        return newSecret {
            metadata {
                name = getHecSecretName(it.name)
                namespace = it.namespace
            }
            data = mapOf(
                hecTokenKey to Base64.encodeBase64String(hecToken.toByteArray()),
                splunkHostKey to Base64.encodeBase64String(splunkUrl.toByteArray()),
                splunkPortKey to Base64.encodeBase64String(splunkPort.toByteArray())
            )
        }
    }
}

fun AuroraDeploymentSpec.shouldCreateFluentbitContainer(): Boolean = this.type in fluentbitContainerSupportedTypes

val fluentbitContainerSupportedTypes = listOf(TemplateType.deploy, TemplateType.development)

private fun getHecSecretName(name: String) = "$name-hec"
private const val FEATURE_FIELD_NAME = "logging"
val AuroraDeploymentSpec.fluentConfigName: String get() = "${this.name}-fluent-config"
val AuroraDeploymentSpec.fluentParserName: String get() = "${this.name}-fluent-parser"
val AuroraDeploymentSpec.bufferSize: Int get() = this["logging/bufferSize"]
val AuroraDeploymentSpec.fluentSideCarContainerName: String get() = "${this.name}-fluent-sidecar"
val AuroraDeploymentSpec.loggingIndex: String? get() = this.getOrNull<String>("logging/index")
const val hecTokenKey: String = "HEC_TOKEN"
const val splunkHostKey: String = "SPLUNK_HOST"
const val splunkPortKey: String = "SPLUNK_PORT"
