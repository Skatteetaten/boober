package no.skatteetaten.aurora.boober.controller.v1

import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.controller.internal.KeyValueResponse
import no.skatteetaten.aurora.boober.facade.UserAnnotationFacade

@RestController
@RequestMapping("/v1/users/annotations")
class UserAnnotationControllerV1(
    private val userAnnotationFacade: UserAnnotationFacade
) {

    @PatchMapping("/{key}")
    fun updateUserAnnotation(@PathVariable key: String, @RequestBody entries: JsonNode): KeyValueResponse<JsonNode> {
        val response = userAnnotationFacade.updateAnnotations(key, entries)
        return KeyValueResponse(items = response)
    }

    @GetMapping
    fun getAllUserAnnotations(): KeyValueResponse<JsonNode> {
        val annotations = userAnnotationFacade.getAnnotations()
        return KeyValueResponse(items = annotations)
    }

    @GetMapping("/{key}")
    fun getUserAnnotations(@PathVariable key: String): KeyValueResponse<JsonNode> {
        val annotations = userAnnotationFacade.getAnnotations().filter { it.key == key }
        return KeyValueResponse(items = annotations)
    }

    @DeleteMapping("/{key}")
    fun deleteUserAnnotation(@PathVariable key: String): KeyValueResponse<JsonNode> {
        val response = userAnnotationFacade.deleteAnnotations(key)
        return KeyValueResponse(items = response)
    }
}
