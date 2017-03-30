package no.skatteetaten.aurora.boober.model

import no.skatteetaten.aurora.boober.utils.createMergeCopy

class AuroraConfig(val aocConfigFiles: Map<String, Map<String, Any?>>) {

    fun getMergedFileForApplication(environmentName: String, applicationName: String): Map<String, Any?> {
        val filesForApplication = getFilesForApplication(environmentName, applicationName)
        val mergedJson = mergeAocConfigFiles(filesForApplication)
        if (!mergedJson.containsKey("envName")) {
            return HashMap(mergedJson).apply { put("envName", environmentName) }
        }
        return mergedJson
    }

    fun getFilesForApplication(environmentName: String, applicationName: String): List<Map<String, Any?>> {

        val requiredFilesForApplication = setOf(
                "about.json",
                "$applicationName.json",
                "$environmentName/about.json",
                "$environmentName/$applicationName.json")

        val filesForApplication: List<Map<String, Any?>> = requiredFilesForApplication.mapNotNull { aocConfigFiles[it] }
        println(filesForApplication)
        if (filesForApplication.size != requiredFilesForApplication.size) {
            val missingFiles = requiredFilesForApplication.filter { it !in aocConfigFiles.keys }
            throw IllegalArgumentException("Unable to execute setup command. Required files missing => $missingFiles")
        }
        return filesForApplication
    }

    private fun mergeAocConfigFiles(filesForApplication: List<Map<String, Any?>>): Map<String, Any?> {

        return filesForApplication.reduce(::createMergeCopy)
    }
}