package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.model.AppConfig
import no.skatteetaten.aurora.boober.model.ProcessConfig
import no.skatteetaten.aurora.boober.model.Result
import no.skatteetaten.aurora.boober.model.TemplateType
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

            val type = TemplateType.valueOf(node.get("type").asText())
            val clazz: Class<*> = if (type == TemplateType.process) {
                ProcessConfig::class.java
            } else {
                AppConfig::class.java
            }

            val config: AppConfig = mapper.reader().forType(clazz).readValue(node.toString())

            return Result(config, files)
        } catch (ex: JsonMappingException) {
            val missingProp = ex.path.map { it.fieldName }.reduce { acc, fieldName -> acc + ".$fieldName" }
            return Result(sources = files, errors = listOf("$missingProp is required"))
        }
    }
}

