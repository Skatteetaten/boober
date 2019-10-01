package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.newOwnerReference
import com.fkorotkov.openshift._import
import com.fkorotkov.openshift.from
import com.fkorotkov.openshift.imageChangeParams
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newDeploymentConfig
import com.fkorotkov.openshift.newDeploymentTriggerPolicy
import com.fkorotkov.openshift.newImageStream
import com.fkorotkov.openshift.newImageStreamImportStatus
import com.fkorotkov.openshift.newNamedTagEventList
import com.fkorotkov.openshift.newTagEvent
import com.fkorotkov.openshift.newTagEventCondition
import com.fkorotkov.openshift.newTagReference
import com.fkorotkov.openshift.spec
import com.fkorotkov.openshift.status
import io.fabric8.openshift.api.model.ImageStreamImport
import no.skatteetaten.aurora.boober.service.ImageStreamImportGenerator
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType

object OpenShiftTestDataBuilders {

    @JvmStatic
    @JvmOverloads
    fun deploymentConfig(triggerType: String? = "ImageChange", dcReplicas: Int = 1): OpenShiftResponse {

        val deploymentConfig = newDeploymentConfig {
            metadata {
                namespace = "aos-test"
                name = "foobar"
                ownerReferences = listOf(newOwnerReference {
                    name = "referanse:default"
                })
            }
            spec {
                replicas = dcReplicas

                triggerType?.let {
                    triggers = listOf(
                        newDeploymentTriggerPolicy {
                            type = triggerType
                            imageChangeParams {
                                from {
                                    name = "referanse:default"
                                    kind = "ImageStreamTag"
                                }
                            }
                        }
                    )
                }
            }
        }
        val dc: JsonNode = jacksonObjectMapper().convertValue(deploymentConfig)
        val url = dc.namespacedNamedUrl
        val command = OpenshiftCommand(OperationType.CREATE, url, dc, dc)
        return OpenShiftResponse(command = command, responseBody = dc, success = true)
    }

    @JvmOverloads
    @JvmStatic
    fun imageStream(
        dockerImageUrl: String = "dockerImageUrl",
        operationType: OperationType = OperationType.UPDATE,
        defaultImageHash: String = "123"
    ): OpenShiftResponse {

        val imageStream = newImageStream {
            metadata {
                namespace = "aos-test"
                name = "foobar"
                resourceVersion = "123"
            }
            spec {
                tags = listOf(
                    newTagReference {
                        name = "default"
                        from {
                            name = dockerImageUrl
                        }
                    }
                )
            }
            status {
                tags = listOf(
                    newNamedTagEventList {
                        items = listOf(
                            newTagEvent {
                                image = defaultImageHash
                                tag = "default"
                            }
                        )
                    }
                )
            }
        }

        val isNode: JsonNode = jacksonObjectMapper().convertValue(imageStream)
        val url = if (operationType == OperationType.CREATE) {
            isNode.namespacedResourceUrl
        } else {
            isNode.namespacedNamedUrl
        }
        val command = OpenshiftCommand(operationType, url, isNode)
        return OpenShiftResponse(command, isNode, true)
    }

    fun imageStreamImport(
        imageHash: String = "123",
        imageStatus: Boolean = true,
        imageErrorMessage: String = ""
    ): ImageStreamImport {

        val status = newImageStreamImportStatus {
            _import {
                status {
                    tags = listOf(newNamedTagEventList {
                        items = listOf(newTagEvent {
                            created = "true"
                            image = imageHash
                            tag = "default"
                        })
                        conditions = listOf(newTagEventCondition {
                            status = imageStatus.toString()
                            message = imageErrorMessage
                        })
                    })
                }
            }
        }
        val isi = ImageStreamImportGenerator.create("test", "foobar", "aos-test")
        isi.status = status
        return isi
    }

    @JvmStatic
    @JvmOverloads
    fun imageStreamImportResponse(imageHash: String = "123"): OpenShiftResponse {

        val imageStreamImport = imageStreamImport(imageHash)

        val isiJson: JsonNode = jacksonObjectMapper().convertValue(imageStreamImport)
        return OpenShiftResponse(
            OpenshiftCommand(
                OperationType.CREATE,
                isiJson.namespacedResourceUrl,
                NullNode.instance
            ), isiJson
        )
    }
}