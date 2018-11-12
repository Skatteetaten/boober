package no.skatteetaten.aurora.boober.controller.v1

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.controller.internal.KeyValueResponse
import no.skatteetaten.aurora.boober.service.UserAnnotationService
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/users/annotations")
class UserAnnotationControllerV1(
    private val userAnnotationService: UserAnnotationService,
    private val userAnnotationResponder: UserAnnotationResponder
) {

    @PatchMapping("/{key}")
    fun updateUserAnnotation(@PathVariable key: String, @RequestBody entries: JsonNode): KeyValueResponse<JsonNode> {
        val response = userAnnotationService.updateAnnotations(key, entries)
        return userAnnotationResponder.create(response)
    }

    @GetMapping
    fun getAllUserAnnotations(): KeyValueResponse<JsonNode> {
        val annotations = userAnnotationService.getAnnotations()
        return userAnnotationResponder.create(annotations)
    }

    @GetMapping("/{key}")
    fun getUserAnnotations(@PathVariable key: String): KeyValueResponse<JsonNode> {
        val annotations = userAnnotationService.getAnnotations().filter { it.key == key }
        return userAnnotationResponder.create(annotations)
    }

    @DeleteMapping("/{key}")
    fun deleteUserAnnotation(@PathVariable key: String): KeyValueResponse<JsonNode> {
        val response = userAnnotationService.deleteAnnotations(key)
        return userAnnotationResponder.create(response)
    }
}

@Component
class UserAnnotationResponder {
    fun create(annotations: Map<String, JsonNode>) = KeyValueResponse(items = annotations)
}