package no.skatteetaten.aurora.boober.feature

import org.apache.commons.codec.digest.DigestUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import com.fkorotkov.kubernetes.batch.jobTemplate
import com.fkorotkov.kubernetes.batch.metadata
import com.fkorotkov.kubernetes.batch.newCronJob
import com.fkorotkov.kubernetes.batch.newJob
import com.fkorotkov.kubernetes.batch.newJobSpec
import com.fkorotkov.kubernetes.batch.spec
import com.fkorotkov.kubernetes.batch.template
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newContainer
import com.fkorotkov.kubernetes.newObjectMeta
import com.fkorotkov.kubernetes.spec
import io.fabric8.kubernetes.api.model.HasMetadata
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.int
import no.skatteetaten.aurora.boober.utils.normalizeLabels
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.validUnixCron

val AuroraDeploymentSpec.jobCommand: List<String>?
    get() = this.getDelimitedStringOrArrayAsSetOrNull("command")?.toList()

val AuroraDeploymentSpec.jobArguments: List<String>?
    get() = this.getDelimitedStringOrArrayAsSetOrNull("arguments")?.toList()

val AuroraDeploymentSpec.jobSchedule: String? get() = this.getOrNull("schedule")

enum class ConcurrencyPolicies {
    Allow, Replace, Forbid
}

@Service
class JobFeature(
    @Value("\${integrations.docker.registry}") val dockerRegistry: String,
    cantusService: CantusService
) : AbstractResolveTagFeature(cantusService) {

    override fun isActive(spec: AuroraDeploymentSpec): Boolean {
        return spec.isJob
    }

    override fun createContext(
        spec: AuroraDeploymentSpec,
        cmd: AuroraContextCommand,
        validationContext: Boolean
    ): Map<String, Any> {

        if (validationContext) {
            return emptyMap()
        }

        return createImageMetadataContext(
            repo = spec.dockerGroup,
            name = spec.artifactId,
            tag = spec.version
        )
    }

    val defaultHandlersForAllTypes = setOf(
        AuroraConfigFieldHandler("serviceAccount"),
        AuroraConfigFieldHandler("replicas"),

        // TODO: Det er ønskelig å kunne sette prometheus konfigurasjon i about filer, selv om de ikke gjør noe for jobs
        AuroraConfigFieldHandler("prometheus", validator = { it.boolean() }, canBeSimplifiedConfig = true),
        AuroraConfigFieldHandler("prometheus/path"),
        AuroraConfigFieldHandler("prometheus/port"),

        AuroraConfigFieldHandler("deployStrategy/type"),
        AuroraConfigFieldHandler("deployStrategy/timeout")
    )

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return header.isJob
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {

        val handlers = gavHandlers(header, cmd) + defaultHandlersForAllTypes

        val cronJobHandlers = if (header.type == TemplateType.cronjob) {
            setOf(
                AuroraConfigFieldHandler("schedule", validator = { it.validUnixCron() }),
                AuroraConfigFieldHandler("suspend", defaultValue = false, validator = { it.boolean() }),
                AuroraConfigFieldHandler("failureCount", defaultValue = 1, validator = { it.int() }),
                AuroraConfigFieldHandler("startingDeadline", defaultValue = 60, validator = { it.int() }),
                AuroraConfigFieldHandler("successCount", defaultValue = 3, validator = { it.int() }),
                AuroraConfigFieldHandler(
                    "concurrentPolicy", defaultValue = ConcurrencyPolicies.Forbid.toString(),
                    validator = { node ->
                        node?.oneOf(ConcurrencyPolicies.values().map { it.toString() })
                    }
                )
            )
        } else {
            null
        }
        return handlers.addIfNotNull(cronJobHandlers)
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {

        val imageMetadata = context.imageMetadata

        val jobSpec = newJobSpec {
            parallelism = 1
            completions = 1
            template {
                metadata {
                    generateName = adc.name
                }
                spec {

                    containers = listOf(
                        newContainer {
                            image = imageMetadata.getFullImagePath()
                            imagePullPolicy = "Always"
                            name = adc.name
                        }
                    )
                    restartPolicy = "Never"
                    dnsPolicy = "ClusterFirst"
                    adc.getOrNull<String>("serviceAccount")?.let {
                        serviceAccount = it
                    }
                }
            }
        }

        val job: HasMetadata = if (adc.type == TemplateType.cronjob) {
            // Please read and understand this before chaning anything here
            // https://kubernetes.io/docs/tasks/job/automated-tasks-with-cron-jobs/
            // https://medium.com/@hengfeng/what-does-kubernetes-cronjobs-startingdeadlineseconds-exactly-mean-cc2117f9795f
            newCronJob {
                metadata = newObjectMeta {
                    name = adc.name
                    namespace = adc.namespace
                }
                spec {
                    schedule = adc.jobSchedule
                    // startingDeadlineSeconds should this be here?
                    successfulJobsHistoryLimit = adc["successCount"]
                    failedJobsHistoryLimit = adc["failureCount"]
                    concurrencyPolicy = adc["concurrentPolicy"]
                    startingDeadlineSeconds = adc["startingDeadline"]
                    suspend = adc["suspend"]
                    jobTemplate {
                        metadata {
                            generateName = adc.name
                        }
                        spec = jobSpec
                    }
                }
            }
        } else {
            newJob {
                metadata = newObjectMeta {
                    generateName = "${adc.name}-"
                    namespace = adc.namespace
                }
                spec = jobSpec
            }
        }
        return setOf(job.generateAuroraResource())
    }

    override fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: FeatureContext
    ) {
        val name = adc.name
        val id = DigestUtils.sha1Hex("${adc.groupId}/$name")
        resources.forEach {
            if (it.resource.kind == "ApplicationDeployment") {
                val labels = mapOf("applicationId" to id).normalizeLabels()
                modifyResource(it, "Added application name and id")
                val ad: ApplicationDeployment = it.resource as ApplicationDeployment
                ad.spec.runnableType = if (adc.type == TemplateType.cronjob) "CronJob" else "Job"
                ad.spec.applicationName = name
                ad.spec.applicationId = id
                ad.metadata.labels = ad.metadata.labels?.addIfNotNull(labels) ?: labels
            }
        }
    }
}
