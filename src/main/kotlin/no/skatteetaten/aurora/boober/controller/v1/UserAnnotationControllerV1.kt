package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.UserAnnotationService
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/users/annotations/{key}")
class UserAnnotationControllerV1(
    private val userAnnotationService: UserAnnotationService,
    private val userAnnotationResponder: UserAnnotationResponder
) {

    @PatchMapping
    fun updateUserAnnotation(@PathVariable key: String, @RequestBody entries: Map<String, Any>): Response {
        val response = userAnnotationService.addAnnotations(key, entries)
        return userAnnotationResponder.create(response)
    }

    @GetMapping
    fun getUserAnnotations(@PathVariable key: String): Response {
        val response = userAnnotationService.getAnnotations(key)
        return userAnnotationResponder.create(response)
    }
}

@Component
class UserAnnotationResponder {
    fun create(annotations: Map<String, Any>) = Response(items = listOf(annotations))
}