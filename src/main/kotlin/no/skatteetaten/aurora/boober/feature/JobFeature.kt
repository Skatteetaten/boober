package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.batch.jobTemplate
import com.fkorotkov.kubernetes.batch.metadata
import com.fkorotkov.kubernetes.batch.newCronJob
import com.fkorotkov.kubernetes.batch.newJob
import com.fkorotkov.kubernetes.batch.spec
import com.fkorotkov.kubernetes.batch.template
import com.fkorotkov.kubernetes.newContainer
import com.fkorotkov.kubernetes.newObjectMeta
import com.fkorotkov.kubernetes.spec
import io.fabric8.kubernetes.api.model.HasMetadata
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.utils.boolean
import org.springframework.beans.factory.annotation.Value

class JobFeature(
    @Value("\${integrations.docker.registry}") val dockerRegistry: String
) : Feature {
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

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {

        return gavHandlers(header, cmd) +
            defaultHandlersForAllTypes +
            // TODO: Not sure if all of these are needed
            setOf(
                AuroraConfigFieldHandler("job/schedule"),
                AuroraConfigFieldHandler("job/suspend", defaultValue = false, validator = { it.boolean() }),
                AuroraConfigFieldHandler("job/command"),
                AuroraConfigFieldHandler("job/arguments"),
                AuroraConfigFieldHandler("job/script"),
                AuroraConfigFieldHandler("job/failureCount", defaultValue = 3),
                AuroraConfigFieldHandler("job/successCount", defaultValue = 3),
                AuroraConfigFieldHandler("job/concurrent", defaultValue = false, validator = { it.boolean() })
            )
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {

        val cronSchedule: String? = adc.getOrNull("job/schedule")
        val meta = newObjectMeta {
            name = adc.name
            namespace = adc.namespace
        }

        val job: HasMetadata = if (cronSchedule != null) {
            newCronJob {
                metadata = meta
                spec {
                    schedule = cronSchedule
                    successfulJobsHistoryLimit = adc["job/successCount"]
                    failedJobsHistoryLimit = adc["job/failureCount"]
                    concurrencyPolicy = adc["job/concurrent"]
                    suspend= adc["job/suspend"]
                    jobTemplate {
                        metadata {
                            generateName = adc.name
                        }
                        spec {
                            template {
                                spec {
                                    containers = listOf(newContainer {
                                        image = "$dockerRegistry/${adc.dockerImagePath}:${adc.dockerTag}"
                                        name = adc.name

                                    })
                                    restartPolicy = "Never"
                                    dnsPolicy = "ClusterFirst"
                                    adc.getOrNull<String>("serviceAccount")?.let {
                                        serviceAccount = it
                                    }

                                }

                            }

                        }
                    }

                }

            }
            // CronJob
        } else {
            // TODO: Not supported yet, work on Cronjob first.
            newJob {
                metadata = meta
                // TODO: I cannot find template here, and that is what openshift jobs require as far as i cn see
                spec {
                    template {

                    }
                }
            }
            // Job
        }
        return setOf(job.generateAuroraResource())
    }
}