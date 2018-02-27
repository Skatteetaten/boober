package no.skatteetaten.aurora.boober.service.internal

import com.fkorotkov.kubernetes.configMap
import com.fkorotkov.kubernetes.emptyDir
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.persistentVolumeClaim
import com.fkorotkov.kubernetes.secret
import com.fkorotkov.kubernetes.spec
import com.fkorotkov.kubernetes.volume
import com.fkorotkov.openshift.deploymentConfig
import com.fkorotkov.openshift.deploymentTriggerPolicy
import com.fkorotkov.openshift.from
import com.fkorotkov.openshift.imageChangeParams
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.recreateParams
import com.fkorotkov.openshift.rollingParams
import com.fkorotkov.openshift.spec
import com.fkorotkov.openshift.strategy
import com.fkorotkov.openshift.template
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.model.MountType
import no.skatteetaten.aurora.boober.platform.AuroraDeployment

object DeploymentConfigGenerator {

    fun create(auroraDeployment: AuroraDeployment, container: List<Container>): DeploymentConfig {

        return deploymentConfig {
            metadata {
                annotations = auroraDeployment.annotations
                apiVersion = "v1"
                labels = auroraDeployment.labels
                name = auroraDeployment.name
                finalizers = null
                ownerReferences = null
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
                        deploymentTriggerPolicy {
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
                        finalizers = null
                        ownerReferences = null
                        labels = auroraDeployment.labels
                    }

                    spec {
                        volumes = auroraDeployment.mounts?.map {
                            volume {
                                name = it.mountName
                                when (it.type) {
                                    MountType.ConfigMap -> configMap {
                                        name = it.volumeName
                                        items = null
                                    }
                                    MountType.Secret -> secret {
                                        secretName = it.volumeName
                                        items = null
                                    }
                                    MountType.PVC -> persistentVolumeClaim {
                                        claimName = it.volumeName
                                    }
                                }
                            }
                        } ?: emptyList()
                        volumes = volumes + volume {
                            name = "application-log-volume"
                            emptyDir()
                        }
                        containers = container
                        restartPolicy = "Always"
                        dnsPolicy = "ClusterFirst"
                        auroraDeployment.serviceAccount?.let { serviceAccount = it }
                        hostAliases = null
                        imagePullSecrets = null
                        tolerations = null
                        initContainers = null
                    }
                }
            }
        }
    }
}