package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.startsWith

/**
 * The header contains the fields that are required to parse the AuroraConfig files and create a merged file for a
 * particular application. This merged file is then the subject to further parsing and validation and may in it self
 * be invalid.
 */
class HeaderMapper(val fields: AuroraConfigFields) {

    companion object {
        private val VALID_SCHEMA_VERSIONS = listOf("v1")

        val handlers = setOf(
          AuroraConfigFieldHandler("schemaVersion", validator = { it.oneOf(VALID_SCHEMA_VERSIONS) }),
          AuroraConfigFieldHandler("type", validator = { it.oneOf(TemplateType.values().map { it.toString() }) }),
          AuroraConfigFieldHandler("baseFile"),
          AuroraConfigFieldHandler("envFile", validator = {
              it?.startsWith("about-", "envFile must start with about")
          }))

        fun create(applicationFiles: List<AuroraConfigFile>, applicationId: ApplicationId): HeaderMapper {

            val fields = AuroraConfigFields.create(handlers, applicationFiles)
            AuroraDeploymentSpecConfigFieldValidator(applicationId, applicationFiles, handlers, fields)
              .validate(false)

            return HeaderMapper(fields)
        }
    }

    val type: TemplateType
        get() = fields.extract("type")
}