package no.skatteetaten.aurora.boober.feature

import org.apache.commons.codec.binary.Base64
import org.springframework.beans.factory.annotation.Value
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
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.utils.addIfNotNull

const val SPLUNK_CONNECT_EXCLUDE_TAG = "splunk.com/exclude"
const val SPLUNK_CONNECT_INDEX_TAG = "splunk.com/index"

val AuroraDeploymentSpec.fluentSideCarContainerName: String get() = "${this.name}-fluent-sidecar"
val AuroraDeploymentSpec.loggingIndex: String? get() = this.getOrNull<String>("logging/index")
val AuroraDeploymentSpec.fluentConfigName: String get() = "${this.name}-fluent-config"
val AuroraDeploymentSpec.fluentParserName: String get() = "${this.name}-fluent-parser"
val AuroraDeploymentSpec.hecSecretName: String get() = "${this.name}-hec"
val AuroraDeploymentSpec.bufferSize: Int get() = this.getOrNull<Int>("logging/bufferSize") ?: 20
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
    cantusService: CantusService,
    @Value("\${splunk.hec.token}") val hecToken: String,
    @Value("\${splunk.hec.url}") val splunkUrl: String,
    @Value("\${splunk.hec.port}") val splunkPort: String,
    @Value("\${splunk.fluentbit.tag}") val fluentBitTag: String
) : AbstractResolveTagFeature(cantusService) {

    override fun isActive(spec: AuroraDeploymentSpec): Boolean {
        val loggingIndex = spec.loggingIndex

        return loggingIndex != null
    }

    override fun createContext(
        spec: AuroraDeploymentSpec,
        cmd: AuroraContextCommand,
        validationContext: Boolean
    ): Map<String, Any> {

        if (validationContext || spec.loggingIndex == null) {
            return emptyMap()
        }

        return createImageMetadataContext(
            repo = "fluent",
            name = "fluent-bit",
            tag = fluentBitTag
        )
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return knownLogs.map { log ->
            AuroraConfigFieldHandler("logging/loggers/$log")
        }
            .addIfNotNull(AuroraConfigFieldHandler("logging/index"))
            .addIfNotNull(AuroraConfigFieldHandler("logging/bufferSize"))
            .toSet()
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        val index = adc.loggingIndex ?: return emptySet()
        if (!shouldGenerateAndModify(adc)) return emptySet()
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
            data = mapOf("fluent-bit.conf" to generateFluentBitConfig(loggerIndexes, adc.name, adc.cluster, adc.bufferSize))
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
        val index = adc.loggingIndex ?: return
        if (index == "") return
        if (!shouldGenerateAndModify(adc)) {
            resources.forEach {
                val template = getTemplate(it) ?: return@forEach
                setTemplateAnnotation(template, SPLUNK_CONNECT_INDEX_TAG, index)
            }
        } else {
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

            val container = createFluentbitContainer(adc, context)
            resources.forEach {
                val template = getTemplate(it) ?: return@forEach
                modifyAuroraResource(it, template, configVolume, parserVolume, container)
            }
        }
    }

    private fun getTemplate(resource: AuroraResource): PodTemplateSpec? {
        var template: PodTemplateSpec? = null
        if (resource.resource.kind == "DeploymentConfig") {
            val dc: DeploymentConfig = resource.resource as DeploymentConfig
            template = dc.spec.template
        } else if (resource.resource.kind == "Deployment") {
            val dc: Deployment = resource.resource as Deployment
            template = dc.spec.template
        }
        return template
    }

    private fun setTemplateAnnotation(template: PodTemplateSpec, annotationKey: String, annotationValue: String) {
        if (template.metadata == null) {
            template.metadata = ObjectMeta()
        }
        if (template.metadata.annotations == null) {
            template.metadata.annotations = mutableMapOf(annotationKey to annotationValue)
        } else {
            template.metadata.annotations.put(
                annotationKey, annotationValue
            )
        }
    }

    private fun shouldGenerateAndModify(adc: AuroraDeploymentSpec): Boolean {
        val isOfType = adc.type in listOf(
            TemplateType.deploy,
            TemplateType.development
        )
        return (adc.loggingIndex != null && isOfType && !adc.isJob)
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
        setTemplateAnnotation(template, SPLUNK_CONNECT_EXCLUDE_TAG, "true")
    }

    private fun createFluentbitContainer(adc: AuroraDeploymentSpec, context: FeatureContext): Container {
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
        val imageMetadata = context.imageMetadata

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
                    "memory" to Quantity("${adc.bufferSize * 5}Mi"),
                    "cpu" to Quantity("300m")
                )
                requests = mapOf(
                    "memory" to Quantity("${adc.bufferSize}Mi"),
                    "cpu" to Quantity("10m")
                )
            }
            image = imageMetadata.getFullImagePath()
        }
    }
}

data class LoggingConfig(
    val name: String,
    val sourceType: String,
    val index: String,
    val filePattern: String,
    val excludePattern: String
)

fun getLoggingIndexes(adc: AuroraDeploymentSpec, defaultIndex: String): List<LoggingConfig> {

    val loggers = getLoggingIndexNames(adc, defaultIndex)

    return loggers.map { (key, value) ->
        val filePattern = getKnownFilePattern(key)
        LoggingConfig(key, getKnownSourceType(key), value, filePattern, getLogRotationExcludePattern(filePattern))
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

fun getLogRotationExcludePattern(logFilePattern: String): String {
    return logFilePattern.replace("*", "*.[1-9]")
}

fun generateFluentBitConfig(loggerIndexes: List<LoggingConfig>, application: String, cluster: String, bufferSize: Int): String {
    val inputs = loggerIndexes.map { log ->
        var multiline = ""
        if (log.sourceType == "log4j") {
            multiline = "Multiline    On\n    Parser_Firstline   log4jMultilineParser"
        }
        """[INPUT]
    Name   tail
    Path   /u01/logs/${log.filePattern}
    Path_Key source
    Exclude_Path ${log.excludePattern}
    Tag    ${log.name}
    DB     /u01/logs/${log.name}.db
    Buffer_Max_Size 512k
    Skip_Long_Lines On
    Mem_Buf_Limit ${bufferSize}MB
    Rotate_Wait 10
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
