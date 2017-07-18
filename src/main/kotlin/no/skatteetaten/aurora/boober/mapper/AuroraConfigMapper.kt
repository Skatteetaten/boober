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
abstract class AuroraConfigMapper(val deployCommand: DeployCommand, val auroraConfig: AuroraConfig) {

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
            val aid = deployCommand.applicationId
            throw ApplicationConfigException(
                    "Config for application ${aid.application} in environment ${aid.environment} contains errors",
                    errors = it.mapNotNull { it })
        }
    }

    protected fun extractPermissions(): Permissions {
        val viewGroups = auroraConfigFields.extractOrNull("permissions/view/groups", { it.textValue().split(" ").toSet() })
        val viewUsers = auroraConfigFields.extractOrNull("permissions/view/users", { it.textValue().split(" ").toSet() })
        val view = if (viewGroups != null || viewUsers != null) {
            Permission(viewGroups, viewUsers)
        } else null

        return Permissions(
                admin = Permission(
                        auroraConfigFields.extract("permissions/admin/groups", { it.textValue().split(" ").toSet() }),
                        auroraConfigFields.extractOrNull("permissions/admin/users", { it.textValue().split(" ").toSet() })),
                view = view)
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

