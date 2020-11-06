package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.configMap
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newConfigMap
import com.fkorotkov.kubernetes.newContainer
import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.kubernetes.newSecret
import com.fkorotkov.kubernetes.newVolume
import com.fkorotkov.kubernetes.newVolumeMount
import com.fkorotkov.kubernetes.valueFrom
import com.fkorotkov.kubernetes.resources
import com.fkorotkov.kubernetes.secretKeyRef
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.findSubKeys
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.apache.commons.codec.binary.Base64
import org.springframework.beans.factory.annotation.Value

val AuroraDeploymentSpec.loggingIndex: String? get() = this.getOrNull<String>("logging/index")
val AuroraDeploymentSpec.fluentConfigName: String? get() = "${this.name}-fluent-config"
val AuroraDeploymentSpec.fluentParserName: String? get() = "${this.name}-fluent-parser"
val AuroraDeploymentSpec.hecSecretName: String? get() = "${this.name}-hec"
val hecTokenKey: String = "HEC_TOKEN"
val splunkHostKey: String = "SPLUNK_HOST"
val splunkPortKey: String = "SPLUNK_PORT"

val logApplication: String = "application"
val logAuditText: String = "audit_text"
val logAuditJson: String = "audit_json"
val logSlow: String = "slow"
val logGC: String = "gc"
val logSensitive: String = "sensitive"
val logStacktrace: String = "stacktrace"
val logAccess: String = "access"
val knownLogs: Set<String> = setOf(logApplication, logAuditText, logAuditJson, logSlow, logGC, logSensitive, logStacktrace, logAccess)

val parserMountPath = "/fluent-bit/parser"
val parsersFileName = "parsers.conf"

/*
Fluentbit sidecar feature provisions fluentd as sidecar with fluent bit configuration based on aurora config.
 */
@org.springframework.stereotype.Service
class FluentbitSidecarFeature(
    @Value("\${splunk.hec.token}") val hecToken: String,
    @Value("\${splunk.hec.url}") val splunkUrl: String,
    @Value("\${splunk.hec.port}") val splunkPort: String
) : Feature {
    override fun enable(header: AuroraDeploymentSpec): Boolean {
        // TODO validate logic
        return header.type in listOf(
                TemplateType.deploy,
                TemplateType.development
        )
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        val customHandlers = cmd.applicationFiles.findSubKeys("logging/custom").flatMap { key ->
            listOf(
                AuroraConfigFieldHandler(
                    "logging/custom/$key/index",
                    defaultValue = header.loggingIndex
                ),
                AuroraConfigFieldHandler(
                    name = "logging/custom/$key/pattern"
                    // validator =
                    // TODO validate that this is requered
                ),
                AuroraConfigFieldHandler(
                    name = "logging/custom/$key/sourcetype"
                    // validator =
                    // TODO validate that this is requered
                )
            )
        }

        val loggerHanlders = knownLogs.map { log ->
            AuroraConfigFieldHandler("logging/loggers/$log")
        }

        return listOf(
            AuroraConfigFieldHandler("logging/index")
        ).addIfNotNull(customHandlers)
            .addIfNotNull(loggerHanlders)
            .toSet()
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {
        val index = adc.loggingIndex ?: return emptySet()
        val loggerIndexes = getLoggingIndexes(adc, cmd, index)

        val fluentParserMap = newConfigMap {
            metadata {
                name = adc.fluentParserName
                namespace = adc.namespace
            }
            data = mapOf(parsersFileName to generateParserConf())
        }

        val fluentConfigMap = newConfigMap {
            metadata {
                name = adc.fluentConfigName
                namespace = adc.namespace
            }
            data = mapOf("fluent-bit.conf" to generateFluentBitConfig(loggerIndexes, adc.name, adc.cluster))
        }

        val hecSecret = newSecret {
            metadata {
                name = adc.hecSecretName
                namespace = adc.namespace
            }
            data = mapOf(hecTokenKey to Base64.encodeBase64String(hecToken.toByteArray()),
                    splunkHostKey to Base64.encodeBase64String(splunkUrl.toByteArray()),
                    splunkPortKey to Base64.encodeBase64String(splunkPort.toByteArray()))
        }
        return setOf(generateResource(fluentParserMap), generateResource(fluentConfigMap), generateResource(hecSecret))
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {
        if (adc.loggingIndex == null) return

        val configVolume = newVolume {
            name = adc.fluentConfigName
            configMap {
                name = adc.fluentConfigName
            }
        }
        val parserVolume = newVolume {
            name = adc.fluentParserName
            configMap {
                name = adc.fluentParserName
            }
        }

        val container = createFluentbitContainer(adc)
        resources.forEach {
            if (it.resource.kind == "DeploymentConfig") {
                modifyAuroraResource(it, configVolume, parserVolume, container)
            } else if (it.resource.kind == "Deployment") {
                modifyAuroraResource(it, configVolume, parserVolume, container)
            }
        }
    }

    private fun modifyAuroraResource(auroraResource: AuroraResource, configVolume: Volume, parserVolume: Volume, container: Container) {
        modifyResource(auroraResource, "Added fluentbit volume and sidecar container")
        val dc: DeploymentConfig = auroraResource.resource as DeploymentConfig
        val podSpec = dc.spec.template.spec
        podSpec.volumes = podSpec.volumes.addIfNotNull(configVolume)
        podSpec.volumes = podSpec.volumes.addIfNotNull(parserVolume)
        podSpec.containers = podSpec.containers.addIfNotNull(container)
    }

    private fun createFluentbitContainer(adc: AuroraDeploymentSpec): Container {
        val hecEnvVariables = listOf(
            newEnvVar {
                name = hecTokenKey
                valueFrom {
                    secretKeyRef {
                        key = hecTokenKey
                        name = adc.hecSecretName
                        optional = false
                    }
                }
            },
            newEnvVar {
                name = splunkHostKey
                valueFrom {
                    secretKeyRef {
                        key = splunkHostKey
                        name = adc.hecSecretName
                        optional = false
                    }
                }
            },
            newEnvVar {
                name = splunkPortKey
                valueFrom {
                    secretKeyRef {
                        key = splunkPortKey
                        name = adc.hecSecretName
                        optional = false
                    }
                }
            }
        )

        return newContainer {
            name = "${adc.name}-fluent-sidecar"
            env = podEnvVariables.addIfNotNull(hecEnvVariables)
            volumeMounts = listOf(
                    newVolumeMount {
                        name = adc.fluentParserName
                        mountPath = parserMountPath
                    }, newVolumeMount {
                        name = adc.fluentConfigName
                        mountPath = "/fluent-bit/etc"
                    }, loggingMount
            )
            resources {
                limits = mapOf(
                        // TODO? Add as config parameter
                        "memory" to Quantity("64Mi"),
                        "cpu" to Quantity("100m")
                )
                requests = mapOf(
                        "memory" to Quantity("20Mi"),
                        "cpu" to Quantity("10m")
                )
            }
            image = "fluent/fluent-bit:latest"
        }
    }
}

data class LoggingConfig(
    val name: String,
    val sourceType: String,
    val index: String,
    val filePattern: String
)

fun getLoggingIndexes(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand, defaultIndex: String): List<LoggingConfig> {
    val result: MutableList<LoggingConfig> = mutableListOf()
    val loggers = cmd.applicationFiles.findSubKeys("logging/loggers").flatMap { key ->
        val value: String = adc.get("logging/loggers/$key")
        listOf(
            LoggingConfig(key, getKnownSourceType(key), value, getKnownFilePattern(key))
        )
    }
    result.addAll(loggers.filter { log -> !(log.index.equals("false")) })
    // Add default loggers if they do not exist
    val applicationLog = loggers.find { logger -> logger.name.equals(logApplication) }
    if (applicationLog == null) {
        val newApplicationLog = LoggingConfig(logApplication, getKnownSourceType(logApplication), defaultIndex, getKnownFilePattern(logApplication))
        result.add(newApplicationLog)
    }
    val accessLog = loggers.find { logger -> logger.name.equals(logAccess) }
    if (accessLog == null) {
        val newAccessLog = LoggingConfig(logAccess, getKnownSourceType(logAccess), defaultIndex, getKnownFilePattern(logAccess))
        result.add(newAccessLog)
    }

    // TODO Add custom loggers
    return result
}

fun getKnownSourceType(logger: String): String {
    var pattern = "log4j"
    when (logger) {
        logAuditJson -> pattern = "_json"
        logAccess -> pattern = "access_combined"
        logGC -> pattern = "gc_log"
    }
    return pattern
}

fun getKnownFilePattern(logger: String): String {
    var pattern = "*.log"
    when (logger) {
        logApplication -> pattern = "*.log"
        logAuditText -> pattern = "*.audit.json"
        logAuditJson -> pattern = "*.audit.text"
        logAccess -> pattern = "*.access"
        logSlow -> pattern = "*.slow"
        logGC -> pattern = "*.gc"
        logSensitive -> pattern = "*.sensitive"
        logStacktrace -> pattern = "*.stacktrace"
    }
    return pattern
}

fun generateFluentBitConfig(loggerIndexes: List<LoggingConfig>, application: String, cluster: String): String {
    val inputs = loggerIndexes.map { log ->
        var multiline = ""
        if (log.sourceType.equals("log4j")) {
            multiline = "Multiline    On\n    Parser_Firstline   log4jMultilineParser"
        }
        """[INPUT]
    Name   tail
    Path   /u01/logs/${log.filePattern}
    Tag    ${log.name}
    Mem_Buf_Limit 10MB
    Key    event
    $multiline"""
    }.joinToString("\n\n")

    val filters = loggerIndexes.map { log ->
        """[FILTER]
    Name modify
    Match ${log.name}
    Set sourcetype ${log.sourceType}
    Set index ${log.index}"""
    }.joinToString("\n\n")

    return """[SERVICE]
    Flush        1
    Daemon       Off
    Log_Level    debug
    Parsers_File $parserMountPath/$parsersFileName

$inputs

$filters

[FILTER]
    Name modify
    Match *
    Add host $ {POD_NAME}
    Add environment $ {POD_NAMESPACE}
    Add nodetype openshift
    Add application $application
    Add cluster $cluster

[FILTER]
    Name nest
    Match *
    Operation nest
    Wildcard environment
    Wildcard application
    Wildcard cluster
    Wildcard nodetype
    Nest_under fields

[OUTPUT]
    Name splunk
    Match *
    Host $ {SPLUNK_HOST}
    Port $ {SPLUNK_PORT}
    Splunk_Token $ {HEC_TOKEN}
    Splunk_Send_Raw On
    TLS         On
    TLS.Verify  Off
    """.replace("$ {", "\${")
}

fun generateParserConf(): String {
    return """[PARSER]
    Name     log4jMultilineParser
    Format   regex
    Regex   ^(?<timestamp>\d{4}-\d{1,2}-\d{1,2}T\d{2}:\d{2}:\d{2},\d*Z) (?<event>.*)
    Time_Key    timestamp
    Time_Format %Y-%m-%dT%H:%M:%S.%L%z
    Time_Keep  Off"""
}
