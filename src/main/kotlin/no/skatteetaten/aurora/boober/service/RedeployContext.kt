package no.skatteetaten.aurora.boober.service

import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.ImageStream
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.utils.*

open class RedeployContext(private val imageStream: ImageStream?, private val deploymentConfig: DeploymentConfig?) {

    open fun didImportImage(openShiftResponse: OpenShiftResponse): Boolean = deploymentConfig?.didImportImage(openShiftResponse)
            ?: false

    open fun isDeploymentRequest(): Boolean = imageStream == null && deploymentConfig != null

    open fun findImageName(): String? = imageStream?.findImageName()

    open fun findImageInformation(): ImageInformation? = deploymentConfig?.findImageInformation()

    open fun verifyResponse(openShiftResponse: OpenShiftResponse): VerificationResult =
            imageStream?.verifyResponse() ?: VerificationResult(success = false, message = "No response found")

}