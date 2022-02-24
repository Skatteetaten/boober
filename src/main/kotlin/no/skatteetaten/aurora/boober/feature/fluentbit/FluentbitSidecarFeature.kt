package no.skatteetaten.aurora.boober.feature.fluentbit

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import com.fkorotkov.kubernetes.configMap
import com.fkorotkov.kubernetes.newVolume
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.PodTemplateSpec
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.feature.AbstractResolveTagFeature
import no.skatteetaten.aurora.boober.feature.FeatureContext
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.findSubKeys
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.pattern
import no.skatteetaten.aurora.boober.utils.required

const val SPLUNK_CONNECT_EXCLUDE_TAG = "splunk.com/exclude"
const val SPLUNK_CONNECT_INDEX_TAG = "splunk.com/index"

const val logApplication: String = "application"
const val logAuditText: String = "audit_text"
const val logAuditJson: String = "audit_json"
const val logSlow: String = "slow"
const val logGC: String = "gc"
const val logSensitive: String = "sensitive"
const val logStacktrace: String = "stacktrace"
const val logAccess: String = "access"

const val parserMountPath = "/fluent-bit/parser"
const val parsersFileName = "parsers.conf"

private const val FEATURE_FIELD_NAME = "logging"
private const val ALLOWED_FILE_PATTERN_REGEX = "^[A-Za-z-*]+\\.[A-Za-z]+\$"

/*
Fluentbit sidecar feature provisions fluentd as sidecar with fluent bit configuration based on aurora config.
 */
@Service
class FluentbitSidecarFeature(
    cantusService: CantusService,
    @Value("\${splunk.hec.token}") val hecToken: String,
    @Value("\${splunk.hec.url}") val splunkUrl: String,
    @Value("\${splunk.hec.port}") val splunkPort: String,
    @Value("\${splunk.fluentbit.tag}") val fluentBitTag: String,
    @Value("\${splunk.fluentbit.resources.cpu.limit}") val cpuLimitFluentbit: String,
    @Value("\${splunk.fluentbit.retry.limit}") val retryLimit: Int? = null
) : AbstractResolveTagFeature(cantusService) {

    override fun isActive(spec: AuroraDeploymentSpec): Boolean {
        return !spec.isFluentbitDisabled()
    }

    override fun createContext(
        spec: AuroraDeploymentSpec,
        cmd: AuroraContextCommand,
        validationContext: Boolean
    ): Map<String, Any> {

        if (validationContext || spec.isFluentbitDisabled()) {
            return emptyMap()
        }

        return createImageMetadataContext(
            repo = "fluent",
            name = "fluent-bit",
            tag = fluentBitTag
        )
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {

        val customLogger = getCustomLoggerHandlers(cmd.applicationFiles)
        val loggers = getLoggerHandlers()

        return setOf(
            AuroraConfigFieldHandler("$FEATURE_FIELD_NAME/index"),
            AuroraConfigFieldHandler("$FEATURE_FIELD_NAME/bufferSize", defaultValue = 20)
        ) + loggers + customLogger
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> = adc.validateFluentbit()

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        if (!adc.shouldCreateFluentbitContainer()) return emptySet()
        if (adc.isFluentbitDisabled()) return emptySet()

        val configuredLoggers = adc.configuredLoggers

        if (configuredLoggers.isEmpty()) return emptySet()

        val fluentConfigMap = adc.createFluentbitConfigMap(configuredLoggers, retryLimit)

        val fluentParserMap = adc.createFluentbitParserConfigmap()

        val hecSecret = adc.createHecSecret(hecToken, splunkUrl, splunkPort)

        return setOf(generateResource(fluentParserMap), generateResource(fluentConfigMap), generateResource(hecSecret))
    }

    override fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: FeatureContext
    ) {
        if (adc.isFluentbitDisabled()) return

        if (adc.shouldCreateFluentbitContainer()) {
            resources.addFluentbitContainer(adc, context)
        } else {
            val configuredLoggers = adc.configuredLoggers
            val applicationLogger = configuredLoggers.find { it.name == logApplication }

            applicationLogger?.index?.let {
                resources.addSplunkConnectAnnotations(applicationLogger.index)
            }
        }
    }

    fun getLoggerHandlers(): Set<AuroraConfigFieldHandler> {
        val logNames =
            setOf(logApplication, logAuditText, logAuditJson, logSlow, logGC, logSensitive, logStacktrace, logAccess)

        return logNames.map {
            AuroraConfigFieldHandler("$FEATURE_FIELD_NAME/loggers/$it")
        }.toSet()
    }

    fun getCustomLoggerHandlers(applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> {
        return applicationFiles.findSubKeys("$FEATURE_FIELD_NAME/custom")
            .flatMap { key ->
                listOf(
                    AuroraConfigFieldHandler(
                        "$FEATURE_FIELD_NAME/custom/$key/index",
                        validator = { it.required("Field is required") }
                    ),
                    AuroraConfigFieldHandler(
                        "$FEATURE_FIELD_NAME/custom/$key/pattern",
                        validator = {
                            it.pattern(
                                ALLOWED_FILE_PATTERN_REGEX,
                                "Is not properly formatted. You need to have exactly one period(.) and conform to the following regex $ALLOWED_FILE_PATTERN_REGEX"
                            )
                        }
                    ),
                    AuroraConfigFieldHandler(
                        "$FEATURE_FIELD_NAME/custom/$key/sourcetype",
                        validator = { it.oneOf(supportedFluentbitSourcetypes) }
                    )
                )
            }
    }

    private fun validate(fieldConstraint: Boolean, validationErrorMessage: String): IllegalArgumentException? =
        if (!fieldConstraint) {
            IllegalArgumentException(validationErrorMessage)
        } else null

    private fun Set<AuroraResource>.addVolumesAndContainer(
        container: Container,
        fluentConfigVolume: Volume,
        fluentParserVolume: Volume
    ) {
        this.forEach {
            modifyResource(it, "Added fluentbit volume, sidecar container and annotation")
            val podTemplate = getPodTemplateSpec(it) ?: return@forEach
            val podSpec = podTemplate.spec

            podSpec.volumes = podSpec.volumes.addIfNotNull(fluentConfigVolume).addIfNotNull(fluentParserVolume)
            podSpec.containers = podSpec.containers.addIfNotNull(container)

            // Add annotation to exclude pods having fluentbit sidecar from being logged by node deployd splunk connect.
            setTemplateAnnotation(podTemplate, SPLUNK_CONNECT_EXCLUDE_TAG, "true")
        }
    }

    fun Set<AuroraResource>.addFluentbitContainer(adc: AuroraDeploymentSpec, context: FeatureContext) {
        val fluentParserVolume = createFluentbitVolume(adc.fluentParserName)
        val fluentConfigVolume = createFluentbitVolume(adc.fluentConfigName)
        val container = adc.createFluentbitContainer(context.imageMetadata.getFullImagePath(), cpuLimitFluentbit)

        this.addVolumesAndContainer(container, fluentConfigVolume, fluentParserVolume)
    }

    private fun createFluentbitVolume(volumeName: String): Volume {
        return newVolume {
            name = volumeName
            configMap {
                name = volumeName
            }
        }
    }

    fun Set<AuroraResource>.addSplunkConnectAnnotations(index: String) {
        this.mapNotNull { getPodTemplateSpec(it) }
            .forEach {
                setTemplateAnnotation(
                    template = it,
                    annotationKey = SPLUNK_CONNECT_INDEX_TAG,
                    annotationValue = index
                )
            }
    }

    private fun getPodTemplateSpec(auroraResource: AuroraResource): PodTemplateSpec? =
        when (val hasMetadata = auroraResource.resource) {
            is Deployment -> hasMetadata.spec.template
            is DeploymentConfig -> hasMetadata.spec.template
            else -> null
        }

    private fun ObjectMeta.addAnnotation(entry: Pair<String, String>) {
        if (this.annotations.isNullOrEmpty()) {
            this.annotations = mutableMapOf(entry)
        } else {
            this.annotations[entry.first] = entry.second
        }
    }

    private fun setTemplateAnnotation(template: PodTemplateSpec, annotationKey: String, annotationValue: String) {
        if (template.metadata == null) {
            template.metadata = ObjectMeta()
        }

        template.metadata.addAnnotation(annotationKey to annotationValue)
    }
}
