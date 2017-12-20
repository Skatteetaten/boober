package no.skatteetaten.aurora.boober.mapper.v1

import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigField
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.mapper.AuroraConfigValidator.Companion.namePattern
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraBuild
import no.skatteetaten.aurora.boober.model.AuroraDeploy
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraLocalTemplate
import no.skatteetaten.aurora.boober.model.AuroraRoute
import no.skatteetaten.aurora.boober.model.AuroraTemplate
import no.skatteetaten.aurora.boober.model.AuroraVolume
import no.skatteetaten.aurora.boober.model.Permission
import no.skatteetaten.aurora.boober.model.Permissions
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.pattern

class AuroraDeploymentSpecMapperV1(val applicationId: ApplicationId) {


    val handlers = listOf(
            AuroraConfigFieldHandler("affiliation", validator = { it.pattern("^[a-z]{1,10}$", "Affiliation can only contain letters and must be no longer than 10 characters") }),
            AuroraConfigFieldHandler("cluster", validator = { it.notBlank("Cluster must be set") }),
            AuroraConfigFieldHandler("permissions/hasAccess"),
            AuroraConfigFieldHandler("permissions/view"),
            AuroraConfigFieldHandler("permissions/adminServiceAccount"),
            AuroraConfigFieldHandler("envName",
                    defaultSource = "folderName",
                    defaultValue = applicationId.environment
            ),
            AuroraConfigFieldHandler("name",
                    defaultValue = applicationId.application,
                    defaultSource = "fileName",
                    validator = { it.pattern(namePattern, "Name must be alphanumeric and no more than 40 characters", false) }),
            AuroraConfigFieldHandler("splunkIndex"),
            AuroraConfigFieldHandler("certificate/commonName"),
            AuroraConfigFieldHandler("certificate"),
            AuroraConfigFieldHandler("database")
    )

    fun createAuroraDeploymentSpec(auroraConfigFields: AuroraConfigFields,
                                   volume: AuroraVolume?,
                                   route: AuroraRoute?,
                                   build: AuroraBuild?,
                                   deploy: AuroraDeploy?,
                                   template: AuroraTemplate?,
                                   localTemplate: AuroraLocalTemplate?
    ): AuroraDeploymentSpec {
        val name: String = auroraConfigFields.extract("name")

        return AuroraDeploymentSpec(
                schemaVersion = auroraConfigFields.extract("schemaVersion"),

                affiliation = auroraConfigFields.extract("affiliation"),
                cluster = auroraConfigFields.extract("cluster"),
                type = auroraConfigFields.extract("type"),
                name = name,
                envName = auroraConfigFields.extract("envName"),
                permissions = extractPermissions(auroraConfigFields),
                fields = createMapForAuroraDeploymentSpecPointers(createFieldsWithValues(auroraConfigFields, build)),
                volume = volume,
                route = route,
                build = build,
                deploy = deploy,
                template = template,
                localTemplate = localTemplate)

    }

    private fun createIncludeSubKeysMap(fields: Map<String, AuroraConfigField>): Map<String, Boolean> {

        val includeSubKeys = mutableMapOf<String, Boolean>()

        fields.entries
                .filter { it.key.split("/").size == 1 }
                .forEach {
                    val key = it.key.split("/")[0]
                    val shouldIncludeSubKeys = it.value.valueOrDefault?.let {
                        !it.isBoolean || it.booleanValue()
                    } ?: false
                    includeSubKeys.put(key, shouldIncludeSubKeys)
                }

        return includeSubKeys
    }



    fun createMapForAuroraDeploymentSpecPointers(auroraConfigFields: Map<String, AuroraConfigField>): Map<String, Map<String, Any?>> {
        val fields = mutableMapOf<String, Any?>()
        val includeSubKeys = createIncludeSubKeysMap(auroraConfigFields)

        auroraConfigFields.entries.forEach { entry ->

            val configField = entry.value
            val configPath = entry.key

            if (configField.value is ObjectNode) {
                return@forEach
            }

            val keys = configPath.split("/")
            if (keys.size > 1 && !includeSubKeys.getOrDefault(keys[0], true)) {
                return@forEach
            }

            var next = fields
            keys.forEachIndexed { index, key ->
                if (index == keys.lastIndex) {
                    next[key] = mutableMapOf(
                            "source" to (configField.source?.configName ?: configField.handler.defaultSource),
                            "value" to configField.valueOrDefault
                    )
                } else {
                    if (next[key] == null) {
                        next[key] = mutableMapOf<String, Any?>()
                    }

                    if (next[key] is MutableMap<*, *>) {
                        next = next[key] as MutableMap<String, Any?>
                    }
                }
            }
        }

        return fields as Map<String, Map<String, Any?>>
    }

    private fun createFieldsWithValues(auroraConfigFields: AuroraConfigFields, build: AuroraBuild?): Map<String, AuroraConfigField> {

        val fields: MutableMap<String, AuroraConfigField> = mutableMapOf()
        val configFields = auroraConfigFields.fields.filterValues { it.source != null || it.handler.defaultValue != null }

        build?.let {
            if (!fields.containsKey("baseImage/name")) {
                fields.put("baseImage/name", AuroraConfigField(AuroraConfigFieldHandler("baseImage/name", defaultValue = it.applicationPlatform.baseImageName)))
            }

            if (!fields.containsKey("baseImage/version")) {
                fields.put("baseImage/version", AuroraConfigField(AuroraConfigFieldHandler("baseImage/version", defaultValue = it.applicationPlatform.baseImageVersion)))
            }
        }
        return configFields + fields
    }

    private fun extractPermissions(auroraConfigFields: AuroraConfigFields): Permissions {
        val view = auroraConfigFields.extractOrNull<String?>("permissions/view")?.let {
            it.split(" ").toSet()
        }?.let {
            Permission(it)
        }

        //if sa present add to hasAccess users.
        val sa = auroraConfigFields.extractOrNull<String?>("permissions/adminServiceAccount")?.let { it.split(" ").toSet() } ?: emptySet()
        val permission = Permissions(
                admin = Permission(
                        auroraConfigFields.extract<String>("permissions/hasAccess").let { it.split(" ").filter { !it.isBlank() }.toSet() }, sa),
                view = view)
        return permission
    }
}

