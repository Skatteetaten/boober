package no.skatteetaten.aurora.boober.service.internal

import com.fkorotkov.kubernetes.emptyDir
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newVolume
import com.fkorotkov.kubernetes.spec
import com.fkorotkov.openshift.from
import com.fkorotkov.openshift.imageChangeParams
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newDeploymentConfig
import com.fkorotkov.openshift.newDeploymentTriggerPolicy
import com.fkorotkov.openshift.recreateParams
import com.fkorotkov.openshift.rollingParams
import com.fkorotkov.openshift.spec
import com.fkorotkov.openshift.strategy
import com.fkorotkov.openshift.template
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.mapper.platform.AuroraDeployment
import no.skatteetaten.aurora.boober.mapper.platform.podVolumes

object DeploymentConfigGenerator {

    fun create(
        auroraDeployment: AuroraDeployment,
        container: List<Container>,
        reference: OwnerReference
    ): DeploymentConfig {

        return newDeploymentConfig {

            metadata {
                ownerReferences = listOf(reference)
                annotations = auroraDeployment.annotations
                apiVersion = "v1"
                labels = auroraDeployment.labels
                name = auroraDeployment.name
            }
            spec {
                strategy {
                    if (auroraDeployment.deployStrategy.type == "rolling") {
                        type = "Rolling"
                        rollingParams {
                            intervalSeconds = 1
                            maxSurge = IntOrString("25%")
                            maxUnavailable = IntOrString(0)
                            timeoutSeconds = auroraDeployment.deployStrategy.timeout.toLong()
                            updatePeriodSeconds = 1L
                        }
                    } else {
                        type = "Recreate"
                        recreateParams {
                            timeoutSeconds = auroraDeployment.deployStrategy.timeout.toLong()
                        }
                    }
                }
                triggers = listOf(
                    newDeploymentTriggerPolicy {
                        type = "ImageChange"
                        imageChangeParams {
                            automatic = true
                            containerNames = auroraDeployment.containers
                                .filter { it.shouldHaveImageChange }
                                .map { it.name }

                            from {
                                name = "${auroraDeployment.name}:${auroraDeployment.tag}"
                                kind = "ImageStreamTag"
                            }
                        }
                    }

                )
                replicas = auroraDeployment.replicas
                selector = mapOf("name" to auroraDeployment.name)
                template {
                    metadata {
                        labels = auroraDeployment.labels
                    }

                    spec {
                        volumes = auroraDeployment.mounts.podVolumes(auroraDeployment.name)
                        volumes = volumes + newVolume {
                            name = "application-log-volume"
                            emptyDir()
                        }
                        containers = container
                        restartPolicy = "Always"
                        dnsPolicy = "ClusterFirst"
                        auroraDeployment.serviceAccount?.let { serviceAccount = it }
                    }
                }
            }
        }
    }
}