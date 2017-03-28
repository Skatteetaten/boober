package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.utils.createMergeCopy

class AocConfig(val aocConfigFiles: Map<String, JsonNode>) {

    fun getMergedFileForApplication(environmentName: String, applicationName: String) : JsonNode {
        val filesForApplication = getFilesForApplication(environmentName, applicationName)
        val mergedJson = mergeAocConfigFiles(filesForApplication)
        if (!mergedJson.has("envName")) {
            (mergedJson as ObjectNode).put("envName", "-$environmentName")
        }
        return mergedJson
    }

    fun getFilesForApplication(environmentName: String, applicationName: String): List<JsonNode> {

        val requiredFilesForApplication = setOf(
                "about.json",
                "$applicationName.json",
                "$environmentName/about.json",
                "$environmentName/$applicationName.json")

        val filesForApplication: List<JsonNode> = requiredFilesForApplication.mapNotNull { aocConfigFiles.get(it) }
        if (filesForApplication.size != requiredFilesForApplication.size) {
            val missingFiles = requiredFilesForApplication.filter { it !in aocConfigFiles.keys }
            throw AocException("Unable to execute setup command. Required files missing => $missingFiles")
        }
        return filesForApplication
    }

    private fun mergeAocConfigFiles(filesForApplication: List<JsonNode>): JsonNode {

        return filesForApplication.reduce(::createMergeCopy)
    }
}