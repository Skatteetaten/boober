package no.skatteetaten.aurora.boober.mapper.v1

import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigField
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.mapper.v1.AuroraDeploymentSpecConfigFieldValidator.Companion.namePattern
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.pattern

class AuroraDeploymentSpecMapperV1(val applicationId: ApplicationId) {


    val handlers = listOf(
            AuroraConfigFieldHandler("affiliation", validator = { it.pattern("^[a-z]{1,10}$", "Affiliation can only contain letters and must be no longer than 10 characters") }),
            AuroraConfigFieldHandler("cluster", validator = { it.notBlank("Cluster must be set") }),
            AuroraConfigFieldHandler("permissions/admin"),
            AuroraConfigFieldHandler("permissions/view"),
            AuroraConfigFieldHandler("permissions/adminServiceAccount"),
            // Max length of OpenShift project names is 63 characters. Project name = affiliation + "-" + envName.
            AuroraConfigFieldHandler("envName", validator = { it.pattern("^[a-z0-9\\-]{0,52}$",
                    "Environment must consist of lower case alphanumeric characters or '-'. It must be no longer than 52 characters.") },
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
            AuroraConfigFieldHandler("database"),
            AuroraConfigFieldHandler("prometheus", defaultValue = true),
            AuroraConfigFieldHandler("prometheus/path", defaultValue = "/prometheus"),
            AuroraConfigFieldHandler("prometheus/port", defaultValue = 8081),
            AuroraConfigFieldHandler("management", defaultValue = true),
            AuroraConfigFieldHandler("management/path", defaultValue = "actuator"),
            AuroraConfigFieldHandler("management/port", defaultValue = "8081"),
            AuroraConfigFieldHandler("deployStrategy/type", defaultValue = "rolling", validator = { it.oneOf(listOf("recreate", "rolling")) }),
            AuroraConfigFieldHandler("deployStrategy/timeout", defaultValue = 180)
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
                type = auroraConfigFields.extract("type"),
                name = name,
                cluster = auroraConfigFields.extract("cluster"),
                environment = AuroraDeployEnvironment(
                        affiliation = auroraConfigFields.extract("affiliation"),
                        envName = auroraConfigFields.extract("envName"),
                        permissions = extractPermissions(auroraConfigFields)
                ),
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

        //if sa present add to admin users.
        val sa = auroraConfigFields.extractOrNull<String?>("permissions/adminServiceAccount")?.let { it.split(" ").toSet() } ?: emptySet()
        val permission = Permissions(
                admin = Permission(
                        auroraConfigFields.extract<String>("permissions/admin").let { it.split(" ").filter { !it.isBlank() }.toSet() }, sa),
                view = view)
        return permission
    }
}

