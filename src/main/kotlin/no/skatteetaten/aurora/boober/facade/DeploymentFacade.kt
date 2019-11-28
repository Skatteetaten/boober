package no.skatteetaten.aurora.boober.facade

import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.ApplicationRef
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.createApplicationDeploymentCommand
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.AuroraDeploymentContextService
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import org.apache.http.HttpStatus
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger { }

@Service
class DeploymentFacade(
    val auroraDeploymentContextService: AuroraDeploymentContextService,
    val auroraConfigService: AuroraConfigService,
    val openShiftClient: OpenShiftClient
) {

    fun deploymentExist(
        ref: AuroraConfigRef,
        adr: List<ApplicationDeploymentRef>
    ): List<GetApplicationDeploymentResponse> {
        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        val applicationRefs =
            adr.map {
                auroraDeploymentContextService.findApplicationRef(
                    AuroraContextCommand(
                        auroraConfig,
                        it,
                        ref
                    )
                )
            }
        return checkApplicationDeploymentsExists(applicationRefs)
    }

    fun executeDelete(applicationRefs: List<ApplicationRef>): List<DeleteApplicationDeploymentResponse> {
        val deleteCommands = applicationRefs.createApplicationDeploymentCommand(OperationType.DELETE)

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

    private fun checkApplicationDeploymentsExists(applicationrefs: List<ApplicationRef>): List<GetApplicationDeploymentResponse> {
        val applicationdeploymentGetCommand = applicationrefs.createApplicationDeploymentCommand(OperationType.GET)

        return applicationdeploymentGetCommand.map {
            val openshiftResponse =
                openShiftClient.performOpenShiftCommand(it.applicationRef.namespace, it.cmd)

            val forbidden = openshiftResponse.httpErrorCode?.let {
                it == HttpStatus.SC_FORBIDDEN
            } ?: false

            if (forbidden) {
                GetApplicationDeploymentResponse(
                    applicationRef = it.applicationRef,
                    exists = false,
                    success = true,
                    message = "OK"
                )
            } else if (!openshiftResponse.success) {
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
}

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
