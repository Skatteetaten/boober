package no.skatteetaten.aurora.boober.controller.v1

import com.fasterxml.jackson.annotation.JsonRawValue
import javax.servlet.http.HttpServletRequest
import javax.validation.Valid
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.controller.NoSuchResourceException
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.v1.AuroraConfigResource.Companion.fromAuroraConfig
import no.skatteetaten.aurora.boober.facade.AuroraConfigFacade
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType
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

private val logger = KotlinLogging.logger {}

// Split auroraConfigServcie i fasade og service
@RestController
@RequestMapping("/v1/auroraconfig/{name}")
class AuroraConfigControllerV1(
    private val auroraConfigFacade: AuroraConfigFacade
) {

    @GetMapping("/{environment}/{application}")
    fun getAdr(
        @PathVariable name: String,
        @PathVariable environment: String,
        @PathVariable application: String
    ): Response {
        val ref = AuroraConfigRef(name, getRefNameFromRequest())
        val adr = ApplicationDeploymentRef(environment, application)
        val files = auroraConfigFacade.findAuroraConfigFilesForApplicationDeployment(ref, adr)

        return Response(items = files.map {
            AuroraConfigFileResource(it.name, it.contents, it.type)
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
                AuroraConfigFileResource(it.name, it.contents, it.type)
            })
        }
        if (application != null || environment != null) {
            throw IllegalArgumentException("Either both application and environment must be set or none of them")
        }

        // TODO: remove all the above and the request param when mokey is changed
        return Response(items = listOf(fromAuroraConfig(name, auroraConfigFacade.findAuroraConfigFiles(ref))))
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
        @RequestBody payload: AuroraConfigResource
    ): Response {

        val ref = AuroraConfigRef(name, getRefNameFromRequest())
        val auroraConfig = payload.toAuroraConfig(ref)
        auroraConfigFacade.validateAuroraConfig(
            auroraConfig,
            resourceValidation = resourceValidation,
            auroraConfigRef = ref
        )
        return Response(items = listOf(auroraConfig).map { fromAuroraConfig(it.name, it.files) })
    }

    @GetMapping("/**")
    fun getAuroraConfigFile(@PathVariable name: String, request: HttpServletRequest): ResponseEntity<Response> {

        val ref = AuroraConfigRef(name, getRefNameFromRequest())
        val fileName = extractFileName(name, request)
        val auroraConfigFile = auroraConfigFacade.findAuroraConfigFile(ref, fileName)
            ?: throw NoSuchResourceException("No such file $fileName in AuroraConfig $name")
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
        val auroraConfig: AuroraConfig =
            auroraConfigFacade.updateAuroraConfigFile(ref, fileName, payload.content, clearQuotes(ifMatchHeader))
        val auroraConfigFile = auroraConfig.findFile(fileName)!!
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

        val auroraConfig = auroraConfigFacade.patchAuroraConfigFile(ref, fileName, payload.content)
        // uhm okai why !! here
        val auroraConfigFile = auroraConfig.findFile(fileName)!!
        return createAuroraConfigFileResponse(auroraConfigFile)
    }

    private fun extractFileName(affiliation: String, request: HttpServletRequest): String {
        val path = "v1/auroraconfig/$affiliation/**"
        return AntPathMatcher().extractPathWithinPattern(path, request.requestURI)
    }

    private fun createAuroraConfigFileResponse(auroraConfigFile: AuroraConfigFile): ResponseEntity<Response> {
        val configFiles = auroraConfigFile
            .let { listOf(AuroraConfigFileResource(it.name, it.contents, it.type)) }
        val response = Response(items = configFiles)
        val headers = HttpHeaders().apply { eTag = "\"${auroraConfigFile.version}\"" }
        return ResponseEntity(response, headers, HttpStatus.OK)
    }
}

data class AuroraConfigResource(
    val name: String,
    val files: List<AuroraConfigFileResource> = listOf()
) {
    fun toAuroraConfig(ref: AuroraConfigRef): AuroraConfig {
        val auroraConfigFiles = files.map { AuroraConfigFile(it.name, it.contents) }
        return AuroraConfig(auroraConfigFiles, ref.name, ref.refName)
    }

    companion object {
        fun fromAuroraConfig(name: String, files: List<AuroraConfigFile>): AuroraConfigResource {
            return AuroraConfigResource(name, files.map { AuroraConfigFileResource(it.name, it.contents, it.type) })
        }
    }
}

data class AuroraConfigFileResource(
    val name: String,
    val contents: String,
    val type: AuroraConfigFileType? = null
)

data class ContentPayload(
    @JsonRawValue
    val content: String
)
