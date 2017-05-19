package no.skatteetaten.aurora.boober.service.mapper

import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.internal.ApplicationConfigException
import no.skatteetaten.aurora.boober.service.mapper.v1.AuroraConfigMapperV1Deploy
import no.skatteetaten.aurora.boober.service.mapper.v1.AuroraConfigMapperV1Process
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient

/*
 This class maps a verisioned AuroraConfig into a AuroraDeploymentConfig
 */
abstract class AuroraConfigMapper(val aid: ApplicationId, val auroraConfig: AuroraConfig, val allFiles: List<AuroraConfigFile>) {

    abstract val auroraConfigFields: AuroraConfigFields
    abstract val fieldHandlers: List<AuroraConfigFieldHandler>

    abstract fun typeValidation(fields: AuroraConfigFields, openShiftClient: OpenShiftClient): List<Exception>

    abstract fun createAuroraDc(): AuroraObjectsConfig

    fun validate(openShiftClient: OpenShiftClient) {
        val fieldMappingErrors = fieldHandlers.mapNotNull { e -> e.validator(auroraConfigFields.fields[e.name]?.value) }

        val typeValidationErrors = typeValidation(auroraConfigFields, openShiftClient)

        val errors = fieldMappingErrors + typeValidationErrors;

        errors.takeIf { it.isNotEmpty() }?.let {
            throw ApplicationConfigException(
                    "Config for application ${aid.applicationName} in environment ${aid.environmentName} contains errors",
                    errors = it.mapNotNull { it.message })
        }
    }

    protected fun extractPermissions(): Permissions {
        return Permissions(Permission(
                auroraConfigFields.extractOrNull("permissions/admin/groups", { it.textValue().split(" ").toSet() }),
                auroraConfigFields.extractOrNull("permissions/admin/users", { it.textValue().split(" ").toSet() })
        ))
    }

    protected fun extractSecret(): Map<String, String>? {
        return auroraConfigFields.extractOrNull("secretFolder", {
            auroraConfig.getSecrets(it.asText())
        })
    }

    companion object {
        @JvmStatic
        fun createMapper(aid: ApplicationId, auroraConfig: AuroraConfig, files: List<AuroraConfigFile>): AuroraConfigMapper {

            val handlers = listOf(AuroraConfigFieldHandler("type"), AuroraConfigFieldHandler("schemaVersion"))

            val fields = AuroraConfigFields.create(handlers, files)

            val type = fields.extract("type", { TemplateType.valueOf(it.textValue()) })
            val schemaVersion = fields.extract("schemaVersion")

            if (schemaVersion != "v1") {
                throw IllegalArgumentException("Only v1 of schema is supported")
            }

            if (type == TemplateType.process) {
                return AuroraConfigMapperV1Process(aid, auroraConfig, files)
            }

            return AuroraConfigMapperV1Deploy(aid, auroraConfig, files)
        }
    }

}

