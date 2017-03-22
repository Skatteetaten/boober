package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.model.Config
import no.skatteetaten.aurora.boober.model.Result
import no.skatteetaten.aurora.boober.utils.createMergeCopy
import org.springframework.stereotype.Service


@Service
class ConfigService(val mapper: ObjectMapper) {

    fun createBooberResult(env: String, app: String, files: Map<String, JsonNode>): Result {

        val names = setOf("about.json", "$env/about.json", "$app.json", "$env/$app.json")
        val missingFiles = names.filter { it !in files.keys }

        if (missingFiles.isNotEmpty()) {
            return Result(sources = files, errors = listOf("Files missing => $missingFiles"))
        }

        val selectedFile = files.filter { it.key in names }.values.toList()
        val mergedJson = selectedFile.reduce(::createMergeCopy)

        return tryToCreateResult(mergedJson, files)
    }


    fun tryToCreateResult(node: JsonNode, files: Map<String, JsonNode>): Result {
        try {
            val config: Config = mapper.reader().forType(Config::class.java).readValue(node.toString())
            return Result(config, files)
        } catch (e: JsonMappingException) {
            val missingProp: String = e.path.map { it.fieldName }.reduce { acc, s -> acc + ".$s" }
            return Result(sources = files, errors = listOf("$missingProp is required"))
        }
    }
}