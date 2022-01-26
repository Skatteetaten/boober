package no.skatteetaten.aurora.boober.feature

import org.apache.commons.codec.binary.Base64
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
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
val AuroraDeploymentSpec.bufferSize: Int get() = this.get<Int>("logging/bufferSize")
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
@Service
class FluentbitSidecarFeature(
    cantusService: CantusService,
    val fluentBitConfigurator: FluentBitConfigurator,
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
            .addIfNotNull(AuroraConfigFieldHandler("logging/bufferSize", defaultValue = 20))
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
            data = mapOf(parsersFileName to fluentBitConfigurator.parserConf())
        }

        val fluentConfigMap = newConfigMap {
            metadata {
                name = adc.fluentConfigName
                namespace = adc.namespace
            }
            data = mapOf(
                "fluent-bit.conf" to fluentBitConfigurator.generateFluentBitConfig(
                    index,
                    loggerIndexes,
                    adc.name,
                    adc.cluster,
                    adc.bufferSize
                )
            )
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
            resources
                .mapNotNull { getTemplate(it) }
                .forEach { setTemplateAnnotation(it, SPLUNK_CONNECT_INDEX_TAG, index) }
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
        val isDeployOrDevType = adc.type in listOf(
            TemplateType.deploy,
            TemplateType.development
        )

        return (isDeployOrDevType && !adc.isJob)
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
        LoggingConfig(
            name = key,
            sourceType = getKnownSourceType(key),
            index = value,
            filePattern = filePattern,
            excludePattern = getLogRotationExcludePattern(filePattern)
        )
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

@Component
class FluentBitConfigurator {

    /**
     * Fluentbit parser config.
     * - timeParser is used for extracting the timestamp from the log and assigning it to the key <time> of the record
     * - multiline-log4j is a MULTILINE_PARSER that groups multiline logs into a single event.
     *   It uses the timestamp to recognize the first line of a log line and continues until it meets another timestamp
     */
    fun parserConf(): String = """
    |[PARSER]
    |   Name        timeParser
    |   Format      regex
    |   Regex       ^(?<timestamp>\d{4}-\d{1,2}-\d{1,2}T\d{2}:\d{2}:\d{2},\d*Z) (.*)
    |   Time_Key    timestamp
    |   Time_Format %Y-%m-%dT%H:%M:%S,%L%z
    |
    |[MULTILINE_PARSER]
    |   name          multiline-log4j
    |   type          regex
    |   key_content   event
    |   flush_timeout 1000
    |   rule          "start_state"   "/^(\d{4}-\d{1,2}-\d{1,2}T\d{2}:\d{2}:\d{2},\d*(Z|\+\d{4}))(.*)$/"  "cont"
    |   rule          "cont"          "/^(?!\d{4}-\d{1,2}-\d{1,2}T\d{2}:\d{2}:\d{2},\d*(Z|\+\d{4}))(.*)$/"  "cont"
    """.trimMargin()

    /**
     * Fluentbit config
     */
    fun generateFluentBitConfig(
        defaultIndex: String,
        loggingConfigs: List<LoggingConfig>,
        application: String,
        cluster: String,
        bufferSize: Int
    ): String {
        val logInputList = getLoggInputList(loggingConfigs, bufferSize)
        val applicationSplunkOutputs = loggingConfigs.joinToString("\n\n") {
            it.run {
                generateSplunkOutput(matcherTag = name, index = index, sourceType = sourceType)
            }
        }

        val fluentbitSplunkOutput =
            generateSplunkOutput(matcherTag = "fluentbit", index = defaultIndex, sourceType = "fluentbit")

        return listOf(
            fluentbitService,
            logInputList,
            fluentbitLogInputAndFilter,
            timeParserFilter,
            multilineLog4jFilter,
            getModifyFilter(application, cluster),
            applicationSplunkOutputs,
            fluentbitSplunkOutput
        ).joinToString("\n\n")
            .replace(
                "$ {",
                "\${"
            ) // Fluentbit uses $(variable) but so does kotling multiline string, so space between $ and ( is used in config and must be replaced here.
    }

    private val fluentbitService: String = """
    |[SERVICE]
    |   Flush        1
    |   Daemon       Off
    |   Log_Level    info
    |   Log_File     /u01/logs/fluentbit
    |   Parsers_File $parserMountPath/$parsersFileName
    """.trimMargin()

    // Fluentibt input for each logging config
    private fun getLoggInputList(
        loggerIndexes: List<LoggingConfig>,
        bufferSize: Int
    ) = loggerIndexes.joinToString("\n\n") { log ->
        """
    |[INPUT]
    |   Name            tail
    |   Path            /u01/logs/${log.filePattern}
    |   Path_Key        source
    |   Exclude_Path    ${log.excludePattern}
    |   Read_From_Head  true
    |   Tag             ${log.name}
    |   DB              /u01/logs/${log.name}.db
    |   Buffer_Max_Size 512k
    |   Skip_Long_Lines On
    |   Mem_Buf_Limit   ${bufferSize}MB
    |   Rotate_Wait     10
    |   Key             event
    """.trimMargin()
    }

    // Input for the log file produced by fluentbit
    // Filters it to stdout
    private val fluentbitLogInputAndFilter = """
    |[INPUT]
    |   Name             tail
    |   Path             /u01/logs/fluentbit
    |   Path_Key         source
    |   Tag              fluentbit
    |   Refresh_Interval 5
    |   Read_from_Head   true
    |   Key              event
    |
    |[FILTER]
    |   Name             stdout
    |   Match            fluentbit
    """.trimMargin()

    // Parser filter to assign it to application tag records
    private val timeParserFilter = """
    |[FILTER]
    |   Name parser
    |   Match application
    |   Key_Name event
    |   Parser timeParser
    |   Preserve_Key On
    |   Reserve_Data On
    """.trimMargin()

    // Multiline filter to assign the multiline_parser to application tag records
    private val multilineLog4jFilter = """
    |[FILTER]
    |   name multiline
    |   match application
    |   multiline.key_content event
    |   multiline.parser multiline-log4j
    """.trimMargin()

    // Fluentbit filter for adding splunk fields for application, cluster, environment, host and nodetype to the record
    private fun getModifyFilter(application: String, cluster: String) = """
    |[FILTER]
    |   Name  modify
    |   Match *
    |   Add   host $ {POD_NAME}
    |   Add   environment $ {POD_NAMESPACE}
    |   Add   nodetype openshift
    |   Add   name $application
    |   Add   cluster $cluster
    """.trimMargin()

    /**
     * Splunk output for a given tag, index and sourectype.
     * The output extracts fields by using event_field and record accessor. Fields are added to the record by a previous filter
     * Event_key extracts the "event" key from the record and uses it to build up the HEC payload
     */
    private fun generateSplunkOutput(matcherTag: String, index: String, sourceType: String): String = """
    |[OUTPUT]
    |   Name             splunk
    |   Match            $matcherTag 
    |   Host             $ {SPLUNK_HOST}
    |   Port             $ {SPLUNK_PORT}
    |   Splunk_token     $ {HEC_TOKEN}
    |   TLS              On
    |   TLS.Verify       Off
    |   event_index      $index
    |   event_sourcetype $sourceType
    |   event_host       $ {POD_NAME}
    |   event_source     ${'$'}source
    |   event_field      application ${'$'}name
    |   event_field      cluster ${'$'}cluster
    |   event_field      environment ${'$'}environment
    |   event_field      nodetype ${'$'}nodetype
    |   event_key        ${'$'}event
    """.trimMargin()
}
