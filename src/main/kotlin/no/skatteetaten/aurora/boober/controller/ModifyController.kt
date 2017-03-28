package no.skatteetaten.aurora.boober.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.model.Config
import no.skatteetaten.aurora.boober.service.ConfigService
import no.skatteetaten.aurora.boober.model.NamespaceResult
import no.skatteetaten.aurora.boober.model.Result
import no.skatteetaten.aurora.boober.service.GitService
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.io.File

@RestController
class ModifyController(val gitService: GitService, val configService: ConfigService, val mapper: ObjectMapper) {

    @PostMapping("/save/{token}/{affiliation}")
    fun setupNamespace(@PathVariable token: String,
                       @PathVariable affiliation: String,
                       @RequestBody input: Map<String, JsonNode>): NamespaceResult {


        val dir = File("/tmp/$token/$affiliation")

        val git = gitService.get(dir)

        input.forEach {
            val json = it.value
            File(dir, it.key).let { mapper.writeValue(it, json) }
        }


        val allEnv = dir.listFiles({ dir -> dir.isDirectory}).filter{ File(dir, "about.json").exists()}

        val res:Map<String, Config> = allEnv.flatMap {
            val env = it.name
            val apps = it.listFiles({ _, name -> name != "about.json" }).toList().map(File::nameWithoutExtension)
            apps.map {
                val files = listOf("about.json", "$env/about.json", "$it.json", "$env/$it.json")

                // TODO: Must collect files from git and replace mapOf
                Pair("$env/$it", configService.createConfigFromAocConfigFiles(env, it, mapOf()))
            }
        }.toMap()

        //if we fail we have to do this.
        git.reset().call()
        return NamespaceResult(res)

    }


}