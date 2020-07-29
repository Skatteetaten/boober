package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class AuroraTemplateService(
    val bitbucketService: BitbucketService,
    @Value("\${integrations.aurora.templates.ref}") val templatesRef: String,
    @Value("\${integrations.aurora.templates.repo}") val templateRepo: String,
    @Value("\${integrations.aurora.templates.project}") val templateProject: String
) {
    fun findTemplate(templateName: String): JsonNode {
        val content = try {
            bitbucketService.getFile(templateProject, templateRepo, "$templateName.json", templatesRef)
        } catch (e: Exception) {
            throw AuroraDeploymentSpecValidationException("Error fetching template=$templateName message=${e.localizedMessage}")
        } ?: throw AuroraDeploymentSpecValidationException("Could not find template=$templateName")

        return try {
            jacksonObjectMapper().readTree(content)
        } catch (e: Exception) {
            throw AuroraDeploymentSpecValidationException("Could not parse template as json message=${e.localizedMessage}")
        }
    }
}
