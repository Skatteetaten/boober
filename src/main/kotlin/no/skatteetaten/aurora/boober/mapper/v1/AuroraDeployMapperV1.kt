package no.skatteetaten.aurora.boober.mapper.v1

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.utils.length
import no.skatteetaten.aurora.boober.utils.notBlank

class AuroraDeployMapperV1(val applicationFiles: List<AuroraConfigFile>, val deployCommand: DeployCommand, val dockerRegistry: String) {


    val dbHandlers = findDbHandlers(applicationFiles)

    val handlers = dbHandlers + listOf(
            AuroraConfigFieldHandler("flags/cert", defaultValue = "false"),
            AuroraConfigFieldHandler("flags/debug", defaultValue = "false"),
            AuroraConfigFieldHandler("flags/alarm", defaultValue = "true"),
            AuroraConfigFieldHandler("flags/rolling", defaultValue = "false"),
            AuroraConfigFieldHandler("resources/cpu/min", defaultValue = "0"),
            AuroraConfigFieldHandler("resources/cpu/max", defaultValue = "2000m"),
            AuroraConfigFieldHandler("resources/memory/min", defaultValue = "128Mi"),
            AuroraConfigFieldHandler("resources/memory/max", defaultValue = "256Mi"),
            AuroraConfigFieldHandler("replicas", defaultValue = "1"),
            AuroraConfigFieldHandler("groupId", validator = { it.length(200, "GroupId must be set and be shorter then 200 characters") }),
            AuroraConfigFieldHandler("artifactId", validator = { it.length(50, "ArtifactId must be set and be shorter then 50 characters") }),
            AuroraConfigFieldHandler("version", validator = { it.notBlank("Version must be set") }),
            AuroraConfigFieldHandler("splunkIndex"),
            AuroraConfigFieldHandler("serviceAccount"),
            AuroraConfigFieldHandler("prometheus/path", defaultValue = "/prometheus"),
            AuroraConfigFieldHandler("prometheus/port", defaultValue = "8081"),
            AuroraConfigFieldHandler("managementPath", defaultValue = ":8081/actuator"),
            AuroraConfigFieldHandler("certificateCn"),
            AuroraConfigFieldHandler("readiness/port", defaultValue = "8080"),
            AuroraConfigFieldHandler("readiness/path"),
            AuroraConfigFieldHandler("readiness/delay", defaultValue = "10"),
            AuroraConfigFieldHandler("readiness/timeout", defaultValue = "1"),
            AuroraConfigFieldHandler("liveness/port"),
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
        val certFlag = auroraConfigFields.extract("flags/cert", { it.asText() == "true" })
        val groupId = auroraConfigFields.extract("groupId")
        val certificateCnDefault = if (certFlag) "$groupId.$name" else null
        val version = auroraConfigFields.extract("version")


        val releaseTo = auroraConfigFields.extractOrNull("releaseTo")

        val artifactId = auroraConfigFields.extract("artifactId")

        val dockerGroup = groupId.replace(".", "_")

        val tag = releaseTo ?: version

        val applicationFile = getApplicationFile(deployCommand.applicationId)
        val overrideFiles = deployCommand.overrideFiles.map { it.name to it.contents }.toMap()

        return AuroraDeploy(
                applicationFile = applicationFile.name,
                releaseTo = releaseTo,
                dockerImagePath = "$dockerRegistry/$dockerGroup/$artifactId",
                dockerTag = tag,
                overrideFiles = overrideFiles,
                flags = AuroraDeploymentConfigFlags(
                        certFlag,
                        auroraConfigFields.extract("flags/debug", { it.asText() == "true" }),
                        auroraConfigFields.extract("flags/alarm", { it.asText() == "true" }),
                        auroraConfigFields.extract("flags/rolling", { it.asText() == "true" })

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
                groupId = groupId,
                artifactId = artifactId,
                version = version,
                splunkIndex = auroraConfigFields.extractOrNull("splunkIndex"),
                database = auroraConfigFields.getDatabases(dbHandlers),

                certificateCn = auroraConfigFields.extractOrDefault("certificateCn", certificateCnDefault),
                serviceAccount = auroraConfigFields.extractOrNull("serviceAccount"),

                webseal = auroraConfigFields.findAll("webseal", {
                    Webseal(
                            auroraConfigFields.extract("webseal/host"),
                            auroraConfigFields.extractOrNull("webseal/roles")
                    )
                }),

                prometheus = auroraConfigFields.findAll("prometheus", {
                    HttpEndpoint(
                            auroraConfigFields.extract("prometheus/path"),
                            auroraConfigFields.extractOrNull("prometheus/port", JsonNode::asInt)
                    )
                }),
                managementPath = auroraConfigFields.extractOrNull("managementPath"),
                liveness = getProbe(auroraConfigFields, "liveness"),
                readiness = getProbe(auroraConfigFields, "readiness")!!
        )
    }


    fun findDbHandlers(applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> {

        val keys = findSubKeys(applicationFiles, "database")

        return keys.map { key ->
            AuroraConfigFieldHandler("database/$key")
        }
    }

    private fun findSubKeys(applicationFiles: List<AuroraConfigFile>, name: String): Set<String> {

        return applicationFiles.flatMap {
            if (it.contents.has(name)) {
                it.contents[name].fieldNames().asSequence().toList()
            } else {
                emptyList()
            }
        }.toSet()
    }

    fun getProbe(auroraConfigFields: AuroraConfigFields, name: String): Probe? {
        val port = auroraConfigFields.extractOrNull("$name/port", JsonNode::asInt)

        if (port == null) {
            return null
        }

        return Probe(
                auroraConfigFields.extractOrNull("$name/path")?.let {
                    if (!it.startsWith("/")) {
                        "/$it"
                    } else it
                },
                port,
                auroraConfigFields.extract("$name/delay", JsonNode::asInt),
                auroraConfigFields.extract("$name/timeout", JsonNode::asInt)
        )
    }


}