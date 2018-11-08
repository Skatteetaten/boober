package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.UserAnnotationService
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/users")
class UserAnnotationControllerV1(
    private val userAnnotationService: UserAnnotationService,
    private val userAnnotationResponder: UserAnnotationResponder
) {

    @PatchMapping("/annotations/{key}")
    fun updateUserAnnotation(@PathVariable key: String, @RequestBody entries: Map<String, Any>): Response {
        val response = userAnnotationService.addAnnotation(key, entries)
        return userAnnotationResponder.create(response)
    }
}

@Component
class UserAnnotationResponder {
    fun create(response: OpenShiftResponse) =
        if (response.success) {
            Response(items = listOf(response))
        } else {
            Response(success = false, message = response.exception ?: "", items = listOf(response))
        }
}