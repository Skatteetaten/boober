package no.skatteetaten.aurora.boober.mapper.v1

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.ApplicationPlatform
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploy
import no.skatteetaten.aurora.boober.model.AuroraDeployStrategy
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

            AuroraConfigFieldHandler("deployStrategy/type", defaultValue = "recreate", validator = { it.oneOf(listOf("recreate", "rolling")) }),
            AuroraConfigFieldHandler("deployStrategy/timeout", defaultValue = "120"),
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
            AuroraConfigFieldHandler("pause", defaultValue = "false"),
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
            AuroraConfigFieldHandler("webseal", defaultValue = "false"),
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
        val pause = auroraConfigFields.extract("pause", { it.asText() == "true" })
        val replicas = auroraConfigFields.extract("replicas", JsonNode::asInt)

        return AuroraDeploy(
                applicationFile = applicationFile.name,
                releaseTo = releaseTo,
                dockerImagePath = "$dockerRegistry/$dockerGroup/$artifactId",
                dockerTag = tag,
                overrideFiles = overrideFiles,
                deployStrategy = AuroraDeployStrategy(
                        auroraConfigFields.extract("deployStrategy/type"),
                        auroraConfigFields.extract("deployStrategy/timeout", { it.asInt() })
                ),
                flags = AuroraDeploymentConfigFlags(
                        certFlag,
                        auroraConfigFields.extract("debug", { it.asText() == "true" }),
                        auroraConfigFields.extract("alarm", { it.asText() == "true" }),
                        pause

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
                replicas = if (pause) 0 else replicas,
                applicationPlatform = ApplicationPlatform.valueOf(auroraConfigFields.extract("applicationPlatform")),
                groupId = groupId,
                artifactId = artifactId,
                version = version,
                splunkIndex = auroraConfigFields.extractOrNull("splunkIndex"),
                database = findDatabases(auroraConfigFields, name),

                certificateCn = auroraConfigFields.extractOrDefault("certificate/commonName", certificateCnDefault),
                serviceAccount = auroraConfigFields.extractOrNull("serviceAccount"),
                webseal = findWebseal(auroraConfigFields),
                prometheus = findPrometheus(auroraConfigFields),
                managementPath = findManagementPath(auroraConfigFields),
                liveness = getProbe(auroraConfigFields, "liveness"),
                readiness = getProbe(auroraConfigFields, "readiness")
        )
    }

    private fun disabledAndNoSubKeys(auroraConfigFields: AuroraConfigFields, name: String): Boolean {

        val noSubKeys = applicationFiles.findSubKeys(name).filter { it != name }.isEmpty()
        val enabled = auroraConfigFields.extract(name, { it.asText() == "true" })

        return !enabled && noSubKeys
    }

    private fun findWebseal(auroraConfigFields: AuroraConfigFields): Webseal? {

        val name = "webseal"

        if (disabledAndNoSubKeys(auroraConfigFields, name)) {
            return null
        }

        return Webseal(
                auroraConfigFields.extractOrNull("webseal/host"),
                auroraConfigFields.extractOrNull("webseal/roles")
        )

    }
    private fun findPrometheus(auroraConfigFields: AuroraConfigFields): HttpEndpoint? {

        val name = "prometheus"

        if (disabledAndNoSubKeys(auroraConfigFields, name)) {
            return null
        }
        return HttpEndpoint(
                auroraConfigFields.extract("$name/path"),
                auroraConfigFields.extractOrNull("$name/port", JsonNode::asInt)
        )
    }

    private fun findManagementPath(auroraConfigFields: AuroraConfigFields): String? {

        val name = "management"

        if (disabledAndNoSubKeys(auroraConfigFields, name)) {
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

        if (disabledAndNoSubKeys(auroraConfigFields, name)) {
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