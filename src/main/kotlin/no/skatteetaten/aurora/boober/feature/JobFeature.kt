package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.batch.jobTemplate
import com.fkorotkov.kubernetes.batch.metadata
import com.fkorotkov.kubernetes.batch.newCronJob
import com.fkorotkov.kubernetes.batch.newJob
import com.fkorotkov.kubernetes.batch.newJobSpec
import com.fkorotkov.kubernetes.batch.spec
import com.fkorotkov.kubernetes.batch.template
import com.fkorotkov.kubernetes.configMap
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newConfigMap
import com.fkorotkov.kubernetes.newContainer
import com.fkorotkov.kubernetes.newObjectMeta
import com.fkorotkov.kubernetes.newVolume
import com.fkorotkov.kubernetes.newVolumeMount
import com.fkorotkov.kubernetes.spec
import io.fabric8.kubernetes.api.model.HasMetadata
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.Paths
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.normalizeLabels
import no.skatteetaten.aurora.boober.utils.oneOf
import org.apache.commons.codec.digest.DigestUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

val AuroraDeploymentSpec.jobCommand: List<String>?
    get() = this.getDelimitedStringOrArrayAsSetOrNull("job/command")?.toList()

val AuroraDeploymentSpec.jobArguments: List<String>?
    get() = this.getDelimitedStringOrArrayAsSetOrNull("job/arguments")?.toList()

val AuroraDeploymentSpec.jobSchedule: String? get() = this.getOrNull("job/schedule")

val AuroraDeploymentSpec.jobType: String get() = this.jobSchedule?.let { "CronJob" } ?: "Job"

enum class ConcurrencyPolicies {
    Allow, Replace, Forbid
}

@Service
class JobFeature(
    @Value("\${integrations.docker.registry}") val dockerRegistry: String
) : Feature {

    // TODO: need routeDefaults hosts here and probably database defaults aswell. Cause they can be in about file
    val defaultHandlersForAllTypes = setOf(
        AuroraConfigFieldHandler("replicas"),
        AuroraConfigFieldHandler("prometheus/path"),
        AuroraConfigFieldHandler("prometheus/port"),
        AuroraConfigFieldHandler("deployStrategy/type"),
        AuroraConfigFieldHandler("deployStrategy/timeout")
    )

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return header.type == TemplateType.job
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        cmd: AuroraContextCommand
    ): List<Exception> {
        val script = adc.getOrNull<String>("job/script")
        if (script != null && (adc.jobArguments != null || adc.jobCommand != null)) {
            throw AuroraDeploymentSpecValidationException("Job script and command/arguments are not compatible. Choose either script or command/arguments")
        }
        return emptyList()
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {

        return gavHandlers(header, cmd) +
            defaultHandlersForAllTypes +
            setOf(
                AuroraConfigFieldHandler("job/schedule"),
                AuroraConfigFieldHandler("job/suspend", defaultValue = false, validator = { it.boolean() }),
                AuroraConfigFieldHandler("job/command"),
                AuroraConfigFieldHandler("job/arguments"),
                AuroraConfigFieldHandler("job/script"),
                AuroraConfigFieldHandler("job/failureCount", defaultValue = 3),
                AuroraConfigFieldHandler("job/successCount", defaultValue = 3),
                AuroraConfigFieldHandler("job/concurrentPolicy", defaultValue = ConcurrencyPolicies.Forbid.toString(),
                    validator = { node ->
                        node?.oneOf(ConcurrencyPolicies.values().map { it.toString() })
                    })
            )
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {

        val script = adc.getOrNull<String>("job/script")
        val scriptConfigMap = script?.let { s ->
            newConfigMap {
                metadata {
                    name = "${adc.name}-script"
                    namespace = adc.namespace
                }
                data = mapOf("script.sh" to s)
            }
        }

        val meta = newObjectMeta {
            name = adc.name
            namespace = adc.namespace
        }

        // TODO: masse flere parametre som kanskje bør settes her.
        val jobSpec = newJobSpec {
            parallelism = 1
            completions = 1
            template {
                metadata {
                    generateName = adc.name
                }
                spec {
                    scriptConfigMap?.let { configMap ->
                        volumes = listOf(newVolume {
                            name = configMap.metadata.name
                            configMap {
                                name = configMap.metadata.name
                            }
                        })
                    }

                    containers = listOf(newContainer {
                        image = "$dockerRegistry/${adc.dockerImagePath}:${adc.dockerTag}"
                        imagePullPolicy = "Always"
                        name = adc.name
                        scriptConfigMap?.let { configMap ->
                            val path = "${Paths.configPath}/script"

                            volumeMounts = listOf(newVolumeMount {
                                mountPath = path
                                name = configMap.metadata.name
                            })
                            command = listOf(
                                "/bin/sh",
                                "$path/script.sh"
                            )
                        }
                        adc.jobArguments?.let {
                            args = it
                        }
                        adc.jobCommand?.let {
                            command = it
                        }
                    })
                    restartPolicy = "Never"
                    dnsPolicy = "ClusterFirst"
                    adc.getOrNull<String>("serviceAccount")?.let {
                        serviceAccount = it
                    }
                }
            }
        }

        val job: HasMetadata = adc.jobSchedule?.let { cron ->
            newCronJob {
                metadata = meta
                spec {
                    schedule = cron
                    // TODO: Should probably validate that these 4 parameters are not valid if schedule is not set, or figure out another way to configure this
                    // startingDeadlineSeconds should this be here?
                    successfulJobsHistoryLimit = adc["job/successCount"]
                    failedJobsHistoryLimit = adc["job/failureCount"]
                    concurrencyPolicy = adc["job/concurrentPolicy"]
                    suspend = adc["job/suspend"]
                    jobTemplate {
                        metadata {
                            generateName = adc.name
                        }
                        spec = jobSpec
                    }
                }
            }
        } ?: newJob {
            metadata = meta
            spec = jobSpec
        }
        return setOf(job.generateAuroraResource()).addIfNotNull(scriptConfigMap?.generateAuroraResource())
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {
        val name = adc.artifactId
        val id = DigestUtils.sha1Hex("${adc.groupId}/$name")
        resources.forEach {
            if (it.resource.kind == "ApplicationDeployment") {
                val labels = mapOf("applicationId" to id).normalizeLabels()
                modifyResource(it, "Added application name and id")
                val ad: ApplicationDeployment = it.resource as ApplicationDeployment
                ad.spec.runnableType = adc.jobType
                ad.spec.applicationName = name
                ad.spec.applicationId = id
                ad.metadata.labels = ad.metadata.labels?.addIfNotNull(labels) ?: labels
            }
        }
    }
}
