package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.treeToValue
import no.skatteetaten.aurora.boober.model.Config
import no.skatteetaten.aurora.boober.model.Result
import no.skatteetaten.aurora.boober.utils.createMergeCopy
import org.springframework.stereotype.Service


@Service
class ConfigService(val mapper: ObjectMapper) {

    /*
    fun createBooberResult2(parentDir: File, jsonFiles: List<String>, overrides: Map<String, JsonNode> = mapOf()): Result {

        val jsonMap: Map<String, JsonNode> = jsonFiles.map{ Pair(it, mapper.readTree(File(parentDir, it)))}.toMap()

        val allJsonValues: List<JsonNode> = jsonMap.values.toList().plus(overrides.values)

        val mergedJson = allJsonValues.merge()

        val config: Config = mapper.treeToValue(mergedJson)
        return Result(config, jsonMap)
    }*/

    fun createBooberResult(env: String, app: String, files: Map<String, JsonNode>): Result {

        val names = setOf("about.json", "$env/about.json", "$app.json", "$env/$app.json")
        val missingFiles = names.filter { it !in files.keys }

        if (missingFiles.isNotEmpty()) {
            return Result(sources = files, errors = listOf("Files missing => $missingFiles"))
        }

        val selectedFile = files.filter{ it.key in names}.values.toList()
        val mergedJson = selectedFile.merge()
        val config: Config = mapper.treeToValue(mergedJson)
        return Result(config = config, sources = files)
    }
}

fun List<JsonNode>.merge() = this.reduce(::createMergeCopy)