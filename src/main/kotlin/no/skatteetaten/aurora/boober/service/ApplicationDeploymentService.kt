package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.controller.v1.ApplicationRef
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

data class ApplicationDeploymentCommand(
    val cmd: OpenshiftCommand,
    val applicationRef: ApplicationRef
)

data class DeleteApplicationDeploymentResponse(
    val applicationRef: ApplicationRef,
    val success: Boolean,
    val message: String
)

data class GetApplicationDeploymentResponse(
    val applicationRef: ApplicationRef,
    val exists: Boolean,
    val success: Boolean,
    val message: String
)

@Service
class ApplicationDeploymentService(
    val openShiftClient: OpenShiftClient
) {
    val logger: Logger = LoggerFactory.getLogger(ApplicationDeploymentService::class.java)

    fun executeDelete(applicationRefs: List<ApplicationRef>): List<DeleteApplicationDeploymentResponse> {
        val deleteCommands = createOpenshiftApplicationDeploymentCommands(applicationRefs, OperationType.DELETE)

        return deleteCommands.map {
            val openshiftResponse =
                openShiftClient.performOpenShiftCommand(it.applicationRef.namespace, it.cmd)

            val applicationDeploymentExists = openshiftResponse.responseBody != null

            if (!openshiftResponse.success) {
                logger.error(openshiftResponse.exception)
                DeleteApplicationDeploymentResponse(
                    applicationRef = it.applicationRef,
                    success = false,
                    message = openshiftResponse.exception
                        ?: "An Openshift Communication error happened when deleting ApplicationDeployment"
                )
            } else {
                val message =
                    if (!applicationDeploymentExists) "ApplicationDeployment does not exist"
                    else openshiftResponse.exception ?: "Application was successfully deleted"

                DeleteApplicationDeploymentResponse(
                    applicationRef = it.applicationRef,
                    success = openshiftResponse.success && applicationDeploymentExists,
                    message = message
                )
            }
        }
    }

    fun checkApplicationDeploymentsExists(applicationrefs: List<ApplicationRef>): List<GetApplicationDeploymentResponse> {
        val applicationdeploymentGetCommand =
            createOpenshiftApplicationDeploymentCommands(applicationrefs, OperationType.GET)

        return applicationdeploymentGetCommand.map {
            val openshiftResponse =
                openShiftClient.performOpenShiftCommand(it.applicationRef.namespace, it.cmd)

            if (!openshiftResponse.success) {
                logger.error(openshiftResponse.exception)
                GetApplicationDeploymentResponse(
                    applicationRef = it.applicationRef,
                    exists = false,
                    success = false,
                    message = openshiftResponse.exception
                        ?: "An error occured when checking if ApplicationDeployment exists"
                )
            } else {

                val applicationDeploymentExists = openshiftResponse.responseBody != null

                GetApplicationDeploymentResponse(
                    applicationRef = it.applicationRef,
                    exists = applicationDeploymentExists,
                    success = true,
                    message = "OK"
                )
            }
        }
    }

    private fun createOpenshiftApplicationDeploymentCommands(
        applicationRefs: List<ApplicationRef>,
        operationType: OperationType
    ) =
        applicationRefs.map {
            val url = OpenShiftResourceClient.generateUrl(
                kind = "ApplicationDeployment",
                name = it.name,
                namespace = it.namespace
            )
            val jsonNode = jacksonObjectMapper().readTree("""{"kind":"applicationdeployment"}""")

            ApplicationDeploymentCommand(
                cmd = OpenshiftCommand(operationType, url = url, payload = jsonNode),
                applicationRef = it
            )
        }
}
