package no.skatteetaten.aurora.boober.controller.v2

import com.fasterxml.jackson.annotation.JsonRawValue
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.v1.AuroraConfigFileResource
import no.skatteetaten.aurora.boober.controller.v1.AuroraConfigResource.Companion.fromAuroraConfig
import no.skatteetaten.aurora.boober.controller.v1.clearQuotes
import no.skatteetaten.aurora.boober.controller.v1.getRefNameFromRequest
import no.skatteetaten.aurora.boober.facade.AuroraConfigFacade
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.servlet.http.HttpServletRequest
import javax.validation.Valid

private val logger = KotlinLogging.logger {}

// TODO: Hvordan skal vi bygge opp path her? Skal vi ha /file prefix for alt som bare jobber på 1 fil?
@RestController
@RequestMapping("/v2/auroraconfig/{name}")
class AuroraConfigControllerV2(
    private val auroraConfigFacade: AuroraConfigFacade
) {

    @GetMapping
    fun get(
        @PathVariable name: String
    ): Response {
        val ref = AuroraConfigRef(name, getRefNameFromRequest())
        return Response(items = listOf(fromAuroraConfig(auroraConfigFacade.findAuroraConfig(ref))))
    }

    @PutMapping
    fun updateAuroraConfigFile(
        @PathVariable name: String,
        @RequestBody @Valid payload: ContentPayloadV2,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) ifMatchHeader: String?,
        request: HttpServletRequest
    ): Response {

        val ref = AuroraConfigRef(name, getRefNameFromRequest())
        val fileName = payload.fileName

        val file = auroraConfigFacade.updateAuroraConfigFile(ref, fileName, payload.content, clearQuotes(ifMatchHeader))

        return Response(
            success = true,
            message = "File $fileName successfully added/updated",
            items = listOf(
                AuroraConfigFileResource(
                    file.name,
                    file.contents,
                    file.version,
                    file.type
                )
            )
        )
    }
}

data class ContentPayloadV2(
    @JsonRawValue
    val content: String,
    val fileName: String
)
