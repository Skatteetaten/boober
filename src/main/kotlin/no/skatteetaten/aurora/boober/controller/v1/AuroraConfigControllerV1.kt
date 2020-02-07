package no.skatteetaten.aurora.boober.controller.v1

import com.fasterxml.jackson.annotation.JsonRawValue
import javax.servlet.http.HttpServletRequest
import javax.validation.Valid
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.v1.AuroraConfigResource.Companion.fromAuroraConfig
import no.skatteetaten.aurora.boober.facade.AuroraConfigFacade
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.ApplicationError
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType
import no.skatteetaten.aurora.boober.model.ErrorDetail
import no.skatteetaten.aurora.boober.model.ErrorType
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.util.AntPathMatcher
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// TODO: Hvordan skal vi bygge opp path her? Skal vi ha /file prefix for alt som bare jobber på 1 fil?
@RestController
@RequestMapping("/v1/auroraconfig/{name}")
class AuroraConfigControllerV1(
    private val auroraConfigFacade: AuroraConfigFacade
) {

    // TODO: Hva skal denne hete? Det mangler tester på denne. Får se på denne i wintercleaning
    @GetMapping("/files/{environment}/{application}")
    fun getAdr(
        @PathVariable name: String,
        @PathVariable environment: String,
        @PathVariable application: String
    ): Response {
        val ref = AuroraConfigRef(name, getRefNameFromRequest())
        val adr = ApplicationDeploymentRef(environment, application)
        val files = auroraConfigFacade.findAuroraConfigFilesForApplicationDeployment(ref, adr)

        return Response(items = files.map {
            AuroraConfigFileResource(it.name, it.contents, it.version, it.type)
        })
    }

    @GetMapping
    fun get(
        @PathVariable name: String,
        @RequestParam("environment", required = false) environment: String?,
        @RequestParam("application", required = false) application: String?
    ): Response {
        val ref = AuroraConfigRef(name, getRefNameFromRequest())

        // This if should be GONE once mokey is changed.
        if (application != null && environment != null) {
            val adr = ApplicationDeploymentRef(environment, application)
            val files = auroraConfigFacade.findAuroraConfigFilesForApplicationDeployment(ref, adr)

            return Response(items = files.map {
                AuroraConfigFileResource(it.name, it.contents, it.version, it.type)
            })
        }
        if (application != null || environment != null) {
            throw IllegalArgumentException("Either both application and environment must be set or none of them")
        }

        // TODO: remove all the above and the request param when mokey is changed
        return Response(items = listOf(fromAuroraConfig(auroraConfigFacade.findAuroraConfig(ref))))
    }

    @GetMapping("/filenames")
    fun getFilenames(
        @PathVariable name: String
    ): Response {
        val ref = AuroraConfigRef(name, getRefNameFromRequest())
        return Response(items = auroraConfigFacade.findAuroraConfigFileNames(ref))
    }

    @PutMapping("/validate")
    fun validateAuroraConfig(
        @PathVariable name: String,
        @RequestParam("resourceValidation", required = false, defaultValue = "false") resourceValidation: Boolean,
        @RequestParam("mergeWithRemoteConfig", required = false, defaultValue = "false") mergeWithRemoteConfig: Boolean,
        @RequestBody payload: AuroraConfigInputResource?
    ): Response {

        val ref = AuroraConfigRef(name, getRefNameFromRequest())
        val auroraConfig =
            payload?.toAuroraConfig(ref) ?: AuroraConfig(
                files = emptyList(),
                name = name,
                ref = "empty",
                resolvedRef = "empty"
            )
        val refsWithWarnings = auroraConfigFacade.validateAuroraConfig(
            auroraConfig,
            resourceValidation = resourceValidation,
            auroraConfigRef = ref,
            mergeWithRemoteConfig = mergeWithRemoteConfig
        )
        val items = refsWithWarnings.map { (adr, warnings) ->
            ApplicationError(
                application = adr.application,
                environment = adr.environment,
                details = warnings.map { ErrorDetail(ErrorType.WARNING, it) }
            )
        }

        return Response(items = items)
    }

    @GetMapping("/**")
    fun getAuroraConfigFile(@PathVariable name: String, request: HttpServletRequest): ResponseEntity<Response> {

        val ref = AuroraConfigRef(name, getRefNameFromRequest())
        val fileName = extractFileName(name, request)
        val auroraConfigFile = auroraConfigFacade.findAuroraConfigFile(ref, fileName)
        return createAuroraConfigFileResponse(auroraConfigFile)
    }

    @PutMapping("/**")
    fun updateAuroraConfigFile(
        @PathVariable name: String,
        @RequestBody @Valid payload: ContentPayload,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) ifMatchHeader: String?,
        request: HttpServletRequest
    ): ResponseEntity<Response> {

        val ref = AuroraConfigRef(name, getRefNameFromRequest())
        val fileName = extractFileName(name, request)
        val auroraConfigFile =
            auroraConfigFacade.updateAuroraConfigFile(ref, fileName, payload.content, clearQuotes(ifMatchHeader))
        return createAuroraConfigFileResponse(auroraConfigFile)
    }

    @PatchMapping("/**")
    fun patchAuroraConfigFile(
        @PathVariable name: String,
        request: HttpServletRequest,
        @RequestBody @Valid payload: ContentPayload
    ): ResponseEntity<Response> {

        val ref = AuroraConfigRef(name, getRefNameFromRequest())
        val fileName = extractFileName(name, request)

        val auroraConfigFile = auroraConfigFacade.patchAuroraConfigFile(
            ref = ref,
            filename = fileName,
            jsonPatchOp = payload.content
        )

        return createAuroraConfigFileResponse(auroraConfigFile)
    }

    private fun extractFileName(affiliation: String, request: HttpServletRequest): String {
        val path = "v1/auroraconfig/$affiliation/**"
        return AntPathMatcher().extractPathWithinPattern(path, request.requestURI)
    }

    private fun createAuroraConfigFileResponse(auroraConfigFile: AuroraConfigFile): ResponseEntity<Response> {
        val configFiles = auroraConfigFile
            .let { listOf(AuroraConfigFileResource(it.name, it.contents, it.version, it.type)) }
        val response = Response(items = configFiles)
        val headers = HttpHeaders().apply { eTag = "\"${auroraConfigFile.version}\"" }
        return ResponseEntity(response, headers, HttpStatus.OK)
    }
}


data class AuroraConfigInputResource(
    val name: String,
    val files: List<AuroraConfigFileResource> = listOf()
) {
    fun toAuroraConfig(ref: AuroraConfigRef): AuroraConfig {
        val auroraConfigFiles = files.map { AuroraConfigFile(it.name, it.contents) }
        return AuroraConfig(auroraConfigFiles, ref.name, ref.refName, "")
    }
}

data class AuroraConfigResource(
    val name: String,
    val ref: String,
    val resolvedRef: String,
    val files: List<AuroraConfigFileResource> = listOf()
) {

    companion object {
        fun fromAuroraConfig(
            auroraConfig: AuroraConfig
        ): AuroraConfigResource {
            return AuroraConfigResource(
                auroraConfig.name,
                auroraConfig.ref,
                auroraConfig.resolvedRef,
                auroraConfig.files.map { AuroraConfigFileResource(it.name, it.contents, it.version, it.type) })
        }
    }
}

data class AuroraConfigFileResource(
    val name: String,
    val contents: String,
    val contentHash: String? = null,
    val type: AuroraConfigFileType? = null
)

data class ContentPayload(
    @JsonRawValue
    val content: String
)
