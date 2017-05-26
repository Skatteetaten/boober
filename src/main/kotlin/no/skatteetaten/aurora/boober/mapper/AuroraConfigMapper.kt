package no.skatteetaten.aurora.boober.mapper

import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.internal.ApplicationConfigException
import no.skatteetaten.aurora.boober.service.internal.ValidationError
import no.skatteetaten.aurora.boober.utils.required
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/*
 This class maps a verisioned AuroraConfig into a AuroraDeploymentConfigDeploy
 */
abstract class AuroraConfigMapper(val aid: DeployCommand, val auroraConfig: AuroraConfig) {

    open val logger: Logger = LoggerFactory.getLogger(AuroraConfigMapper::class.java)

    abstract val auroraConfigFields: AuroraConfigFields
    abstract val fieldHandlers: List<AuroraConfigFieldHandler>

    abstract fun toAuroraDeploymentConfig(): AuroraDeploymentConfig

    fun validate() {
        val errors = fieldHandlers.mapNotNull { e ->
            val auroraConfigField = auroraConfigFields.fields[e.name]

            e.validator(auroraConfigField?.value)?.let {
                ValidationError(it.localizedMessage, auroraConfigField)
            }
        }

        errors.takeIf { it.isNotEmpty() }?.let {
            logger.debug("{}", it)
            throw ApplicationConfigException(
                    "Config for application ${aid.applicationName} in environment ${aid.environmentName} contains errors",
                    errors = it.mapNotNull { it })
        }
    }

    protected fun extractPermissions(): Permissions {
        return Permissions(Permission(
                auroraConfigFields.extract("permissions/admin/groups", { it.textValue().split(" ").toSet() }),
                auroraConfigFields.extractOrNull("permissions/admin/users", { it.textValue().split(" ").toSet() })
        ))
    }

    protected fun extractSecret(): Map<String, String>? {
        return auroraConfigFields.extractOrNull("secretFolder", {
            auroraConfig.getSecrets(it.asText())
        })
    }

    companion object {
        val baseHandlers = listOf(
                AuroraConfigFieldHandler("schemaVersion"),
                AuroraConfigFieldHandler("type", validator = { it.required("Type is required") }))
    }

}

