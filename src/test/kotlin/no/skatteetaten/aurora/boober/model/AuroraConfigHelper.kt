package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.openshift._import
import com.fkorotkov.openshift.image
import com.fkorotkov.openshift.newImageStreamImport
import com.fkorotkov.openshift.newNamedTagEventList
import com.fkorotkov.openshift.newTagEvent
import com.fkorotkov.openshift.newTagEventCondition
import com.fkorotkov.openshift.status
import io.fabric8.openshift.api.model.ImageStreamImport
import java.io.File
import java.nio.charset.Charset

class AuroraConfigHelper

val folder = File(AuroraConfigHelper::class.java.getResource("/samples/config").file)

fun getAuroraConfigSamples(): AuroraConfig {
    val files = folder.walkBottomUp()
        .onEnter { it.name != "secret" }
        .filter { it.isFile }
        .associate { it.relativeTo(folder).path to it }

    val nodes = files.map {
        it.key to it.value.readText(Charset.defaultCharset())
    }.toMap()

    return AuroraConfig(nodes.map { AuroraConfigFile(it.key, it.value, false) }, "aos", "master")
}

@JvmOverloads
fun createAuroraConfig(
    aid: ApplicationDeploymentRef,
    affiliation: String = "aos",
    additionalFile: String? = null,
    refName: String = "master"
): AuroraConfig {
    val files = getSampleFiles(aid, additionalFile)

    return AuroraConfig(files.map { AuroraConfigFile(it.key, it.value, false) }, affiliation, refName)
}

@JvmOverloads
fun getSampleFiles(aid: ApplicationDeploymentRef, additionalFile: String? = null): Map<String, String> {

    return collectFiles(
        "about.json",
        "${aid.application}.json",
        "${aid.environment}/about.json",
        "${aid.environment}/${aid.application}.json",
        additionalFile?.let { it } ?: ""
    )
}

fun getResultFiles(aid: ApplicationDeploymentRef): Map<String, JsonNode?> {
    val baseFolder = File(AuroraConfigHelper::class.java
        .getResource("/samples/result/${aid.environment}/${aid.application}").file)

    return getFiles(baseFolder)
}

private fun getFiles(baseFolder: File, name: String = ""): Map<String, JsonNode?> {
    return baseFolder.listFiles().toHashSet().map {
        val json = convertFileToJsonNode(it)

        var appName = json?.at("/metadata/name")?.textValue()
        if (name.isNotBlank()) {
            appName = name
        }

        val file = json?.at("/kind")?.textValue() + "/" + appName
        file.toLowerCase() to json
    }.toMap()
}

private fun collectFiles(vararg fileNames: String): Map<String, String> {

    return fileNames.filter { !it.isBlank() }.map { it to File(folder, it).readText(Charset.defaultCharset()) }.toMap()
}

private fun convertFileToJsonNode(file: File): JsonNode? {

    val mapper = jacksonObjectMapper()
    return mapper.readValue(file, JsonNode::class.java)
}

@JvmOverloads
fun imageStreamImport(imageHash: String = "123", imageStatus: Boolean = true, imageErrorMessage: String = ""): ImageStreamImport {
    return newImageStreamImport {
        status {
            _import {
                status {
                    tags = listOf(newNamedTagEventList {
                        items = listOf(newTagEvent {
                            created = "true"
                            image = imageHash
                        })
                        conditions = listOf(newTagEventCondition {
                            status = imageStatus.toString()
                            message = imageErrorMessage
                        })
                    })
                }
            }
        }
    }
}
