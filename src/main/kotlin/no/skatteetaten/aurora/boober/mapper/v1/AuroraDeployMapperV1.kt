package no.skatteetaten.aurora.boober.mapper.v1

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.ApplicationPlatform
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploy
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigFlags
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigResource
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigResources
import no.skatteetaten.aurora.boober.model.Database
import no.skatteetaten.aurora.boober.model.HttpEndpoint
import no.skatteetaten.aurora.boober.model.Probe
import no.skatteetaten.aurora.boober.model.Webseal
import no.skatteetaten.aurora.boober.model.findSubKeys
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.length
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.oneOf

class AuroraDeployMapperV1(val applicationId: ApplicationId, val applicationFiles: List<AuroraConfigFile>, val overrideFiles: List<AuroraConfigFile>, val dockerRegistry: String) {

    val dbHandlers = findDbHandlers(applicationFiles)

    val handlers = dbHandlers + listOf(

            AuroraConfigFieldHandler("deployStrategy", defaultValue = "recreate", validator = { it.oneOf(listOf("recreate", "rolling")) }),
            AuroraConfigFieldHandler("database", defaultValue = "false"),
            AuroraConfigFieldHandler("debug", defaultValue = "false"),
            AuroraConfigFieldHandler("alarm", defaultValue = "true"),
            AuroraConfigFieldHandler("resources/cpu/min", defaultValue = "0"),
            AuroraConfigFieldHandler("resources/cpu/max", defaultValue = "2000m"),
            AuroraConfigFieldHandler("resources/memory/min", defaultValue = "128Mi"),
            AuroraConfigFieldHandler("resources/memory/max", defaultValue = "256Mi"),
            AuroraConfigFieldHandler("replicas", defaultValue = "1"),
            AuroraConfigFieldHandler("applicationPlatform", defaultValue = "java", validator = { it.oneOf(ApplicationPlatform.values().map { it.name }) }),
            AuroraConfigFieldHandler("groupId", validator = { it.length(200, "GroupId must be set and be shorter then 200 characters") }),
            AuroraConfigFieldHandler("artifactId", validator = { it.length(50, "ArtifactId must be set and be shorter then 50 characters") }),
            AuroraConfigFieldHandler("version", validator = { it.notBlank("Version must be set") }),
            AuroraConfigFieldHandler("splunkIndex"),
            AuroraConfigFieldHandler("serviceAccount"),
            AuroraConfigFieldHandler("prometheus", defaultValue = "true"),
            AuroraConfigFieldHandler("prometheus/path", defaultValue = "/prometheus"),
            AuroraConfigFieldHandler("prometheus/port", defaultValue = "8081"),
            AuroraConfigFieldHandler("management", defaultValue = "true"),
            AuroraConfigFieldHandler("management/path", defaultValue = "actuator"),
            AuroraConfigFieldHandler("management/port", defaultValue = "8081"),
            AuroraConfigFieldHandler("certificate/commonName"),
            AuroraConfigFieldHandler("certificate", defaultValue = "false"),
            AuroraConfigFieldHandler("readiness", defaultValue = "true"),
            AuroraConfigFieldHandler("readiness/port", defaultValue = "8080"),
            AuroraConfigFieldHandler("readiness/path"),
            AuroraConfigFieldHandler("readiness/delay", defaultValue = "10"),
            AuroraConfigFieldHandler("readiness/timeout", defaultValue = "1"),
            AuroraConfigFieldHandler("liveness", defaultValue = "false"),
            AuroraConfigFieldHandler("liveness/port", defaultValue = "8080"),
            AuroraConfigFieldHandler("liveness/path"),
            AuroraConfigFieldHandler("liveness/delay", defaultValue = "10"),
            AuroraConfigFieldHandler("liveness/timeout", defaultValue = "1"),
            AuroraConfigFieldHandler("webseal/host"),
            AuroraConfigFieldHandler("webseal/roles"),
            AuroraConfigFieldHandler("releaseTo")

    )

    fun getApplicationFile(applicationId: ApplicationId): AuroraConfigFile {
        val fileName = "${applicationId.environment}/${applicationId.application}.json"
        val file = applicationFiles.find { it.name == fileName && !it.override }
        return file ?: throw IllegalArgumentException("Should find applicationFile $fileName")
    }

    fun deploy(auroraConfigFields: AuroraConfigFields): AuroraDeploy? {
        val name = auroraConfigFields.extract("name")
        val certFlag = auroraConfigFields.extract("certificate", { it.asText() == "true" })
        val groupId = auroraConfigFields.extract("groupId")

        val certificateCnDefault = if (certFlag) "$groupId.$name" else null
        val version = auroraConfigFields.extract("version")


        val releaseTo = auroraConfigFields.extractOrNull("releaseTo")

        val artifactId = auroraConfigFields.extract("artifactId")

        val dockerGroup = groupId.replace(".", "_")

        val tag = releaseTo ?: version

        val applicationFile = getApplicationFile(applicationId)
        val overrideFiles = overrideFiles.map { it.name to it.contents }.toMap()

        return AuroraDeploy(
                applicationFile = applicationFile.name,
                releaseTo = releaseTo,
                dockerImagePath = "$dockerRegistry/$dockerGroup/$artifactId",
                dockerTag = tag,
                overrideFiles = overrideFiles,
                deployStrategy = auroraConfigFields.extract("deployStrategy"),
                flags = AuroraDeploymentConfigFlags(
                        certFlag,
                        auroraConfigFields.extract("debug", { it.asText() == "true" }),
                        auroraConfigFields.extract("alarm", { it.asText() == "true" })

                ),
                resources = AuroraDeploymentConfigResources(
                        memory = AuroraDeploymentConfigResource(
                                min = auroraConfigFields.extract("resources/memory/min"),
                                max = auroraConfigFields.extract("resources/memory/max")
                        ),
                        cpu = AuroraDeploymentConfigResource(
                                min = auroraConfigFields.extract("resources/cpu/min"),
                                max = auroraConfigFields.extract("resources/cpu/max")
                        )
                ),
                replicas = auroraConfigFields.extract("replicas", JsonNode::asInt),
                applicationPlatform = ApplicationPlatform.valueOf(auroraConfigFields.extract("applicationPlatform")),
                groupId = groupId,
                artifactId = artifactId,
                version = version,
                splunkIndex = auroraConfigFields.extractOrNull("splunkIndex"),
                database = findDatabases(auroraConfigFields, name),

                certificateCn = auroraConfigFields.extractOrDefault("certificate/commonName", certificateCnDefault),
                serviceAccount = auroraConfigFields.extractOrNull("serviceAccount"),

                webseal = auroraConfigFields.findAll("webseal", {
                    Webseal(
                            auroraConfigFields.extract("webseal/host"),
                            auroraConfigFields.extractOrNull("webseal/roles")
                    )
                }),

                prometheus = findPrometheus(auroraConfigFields),
                managementPath = findManagementPath(auroraConfigFields),
                liveness = getProbe(auroraConfigFields, "liveness"),
                readiness = getProbe(auroraConfigFields, "readiness")
        )
    }

    private fun findPrometheus(auroraConfigFields: AuroraConfigFields): HttpEndpoint? {

        val name = "prometheus"
        val noSubKeys = applicationFiles.findSubKeys(name).filter { it != name }.isEmpty()
        val enabled = auroraConfigFields.extract(name, { it.asText() == "true" })

        if (!enabled && noSubKeys) {
            return null
        }
        return HttpEndpoint(
                auroraConfigFields.extract("$name/path"),
                auroraConfigFields.extractOrNull("$name/port", JsonNode::asInt)
        )
    }

    private fun findManagementPath(auroraConfigFields: AuroraConfigFields): String? {

        val name = "management"
        val noSubKeys = applicationFiles.findSubKeys(name).filter { it != name }.isEmpty()
        val enabled = auroraConfigFields.extract(name, { it.asText() == "true" })
        if (!enabled && noSubKeys) {
            return null
        }

        val path = auroraConfigFields.extract("management/path").ensureStartWith("/")
        val port = auroraConfigFields.extract("management/port").ensureStartWith(":")
        return "$port$path"
    }

    private fun findDatabases(auroraConfigFields: AuroraConfigFields, name: String): List<Database> {

        val enabled = auroraConfigFields.extract("database", { it.asText() == "true" })
        if (enabled) {
            return listOf(Database(name = name))
        }

        return auroraConfigFields.getDatabases(dbHandlers)
    }


    fun findDbHandlers(applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> {

        val keys = applicationFiles.findSubKeys("database")

        return keys.map { key ->
            AuroraConfigFieldHandler("database/$key")
        }
    }


    fun getProbe(auroraConfigFields: AuroraConfigFields, name: String): Probe? {

        val noSubKeys = applicationFiles.findSubKeys(name).filter { it != name }.isEmpty()
        val enabled = auroraConfigFields.extract(name, { it.asText() == "true" })
        if (noSubKeys && !enabled) {
            return null
        }

        return Probe(
                auroraConfigFields.extractOrNull("$name/path")?.let {
                    if (!it.startsWith("/")) {
                        "/$it"
                    } else it
                },
                auroraConfigFields.extract("$name/port", JsonNode::asInt),
                auroraConfigFields.extract("$name/delay", JsonNode::asInt),
                auroraConfigFields.extract("$name/timeout", JsonNode::asInt)
        )
    }


}