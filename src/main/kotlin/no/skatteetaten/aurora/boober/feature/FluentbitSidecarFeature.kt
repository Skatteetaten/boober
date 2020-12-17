package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.configMap
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newConfigMap
import com.fkorotkov.kubernetes.newContainer
import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.kubernetes.newSecret
import com.fkorotkov.kubernetes.newVolume
import com.fkorotkov.kubernetes.newVolumeMount
import com.fkorotkov.kubernetes.resources
import com.fkorotkov.kubernetes.secretKeyRef
import com.fkorotkov.kubernetes.valueFrom
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.PodTemplateSpec
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.apache.commons.codec.binary.Base64
import org.springframework.beans.factory.annotation.Value

const val SPLUNK_CONNECT_EXCLUDE_TAG = "splunk.com/exclude"

val AuroraDeploymentSpec.fluentSideCarContainerName: String get() = "${this.name}-fluent-sidecar"
val AuroraDeploymentSpec.loggingIndex: String? get() = this.getOrNull<String>("logging/index")
val AuroraDeploymentSpec.fluentConfigName: String? get() = "${this.name}-fluent-config"
val AuroraDeploymentSpec.fluentParserName: String? get() = "${this.name}-fluent-parser"
val AuroraDeploymentSpec.hecSecretName: String? get() = "${this.name}-hec"
const val hecTokenKey: String = "HEC_TOKEN"
const val splunkHostKey: String = "SPLUNK_HOST"
const val splunkPortKey: String = "SPLUNK_PORT"

const val logApplication: String = "application"
const val logAuditText: String = "audit_text"
const val logAuditJson: String = "audit_json"
const val logSlow: String = "slow"
const val logGC: String = "gc"
const val logSensitive: String = "sensitive"
const val logStacktrace: String = "stacktrace"
const val logAccess: String = "access"
val knownLogs: Set<String> =
    setOf(logApplication, logAuditText, logAuditJson, logSlow, logGC, logSensitive, logStacktrace, logAccess)

const val parserMountPath = "/fluent-bit/parser"
const val parsersFileName = "parsers.conf"

/*
Fluentbit sidecar feature provisions fluentd as sidecar with fluent bit configuration based on aurora config.
 */
@org.springframework.stereotype.Service
class FluentbitSidecarFeature(
    @Value("\${splunk.hec.token}") val hecToken: String,
    @Value("\${splunk.hec.url}") val splunkUrl: String,
    @Value("\${splunk.hec.port}") val splunkPort: String,
    @Value("\${splunk.fluentbit.image}") val fluentBitImage: String
) : Feature {
    override fun enable(header: AuroraDeploymentSpec): Boolean {
        val isOfType = header.type in listOf(
            TemplateType.deploy,
            TemplateType.development
        )
        return isOfType && !header.isJob
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return knownLogs.map { log ->
            AuroraConfigFieldHandler("logging/loggers/$log")
        }
            .addIfNotNull(AuroraConfigFieldHandler("logging/index"))
            .toSet()
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        val index = adc.loggingIndex ?: return emptySet()
        val loggerIndexes = getLoggingIndexes(adc, index)

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
            data = mapOf(
                hecTokenKey to Base64.encodeBase64String(hecToken.toByteArray()),
                splunkHostKey to Base64.encodeBase64String(splunkUrl.toByteArray()),
                splunkPortKey to Base64.encodeBase64String(splunkPort.toByteArray())
            )
        }
        return setOf(generateResource(fluentParserMap), generateResource(fluentConfigMap), generateResource(hecSecret))
    }

    override fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: FeatureContext
    ) {
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
                val dc: DeploymentConfig = it.resource as DeploymentConfig
                val template = dc.spec.template
                modifyAuroraResource(it, template, configVolume, parserVolume, container)
            } else if (it.resource.kind == "Deployment") {
                val dc: Deployment = it.resource as Deployment
                val template = dc.spec.template
                modifyAuroraResource(it, template, configVolume, parserVolume, container)
            }
        }
    }

    private fun modifyAuroraResource(
        auroraResource: AuroraResource,
        template: PodTemplateSpec,
        configVolume: Volume,
        parserVolume: Volume,
        container: Container
    ) {
        val podSpec = template.spec
        modifyResource(auroraResource, "Added fluentbit volume, sidecar container and annotation")
        podSpec.volumes = podSpec.volumes.addIfNotNull(configVolume)
        podSpec.volumes = podSpec.volumes.addIfNotNull(parserVolume)
        podSpec.containers = podSpec.containers.addIfNotNull(container)
        // Add annotation to exclude pods having fluentbit sidecar from being logged by node deployd splunk connect.
        if (template.metadata == null) {
            template.metadata = ObjectMeta()
        }
        if (template.metadata.annotations == null) {
            template.metadata.annotations = mutableMapOf(SPLUNK_CONNECT_EXCLUDE_TAG to "true")
        } else {
            template.metadata.annotations.put(
                SPLUNK_CONNECT_EXCLUDE_TAG, "true"
            )
        }
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
            name = adc.fluentSideCarContainerName
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
            image = fluentBitImage
        }
    }
}

data class LoggingConfig(
    val name: String,
    val sourceType: String,
    val index: String,
    val filePattern: String
)

fun getLoggingIndexes(adc: AuroraDeploymentSpec, defaultIndex: String): List<LoggingConfig> {

    val loggers = getLoggingIndexNames(adc, defaultIndex)

    return loggers.map { (key, value) ->
        LoggingConfig(key, getKnownSourceType(key), value, getKnownFilePattern(key))
    }
}

private fun getLoggingIndexNames(
    adc: AuroraDeploymentSpec,
    defaultIndex: String
): Map<String, String> {

    val loggers: MutableMap<String, String> =
        adc.associateSubKeys<String>("logging/loggers").filter {
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

fun getKnownSourceType(logger: String): String {
    return when (logger) {
        logAuditJson -> "_json"
        logAccess -> "access_combined"
        logGC -> "gc_log"
        else -> "log4j"
    }
}

fun getKnownFilePattern(logger: String): String {
    return when (logger) {
        logApplication -> "*.log"
        logAuditText -> "*.audit.json"
        logAuditJson -> "*.audit.text"
        logAccess -> "*.access"
        logSlow -> "*.slow"
        logGC -> "*.gc"
        logSensitive -> "*.sensitive"
        logStacktrace -> "*.stacktrace"
        else -> "*.log"
    }
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
    Log_Level    info
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
    Time_Format %Y-%m-%dT%H:%M:%S,%L%z
    Time_Keep  Off"""
}
