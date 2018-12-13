package no.skatteetaten.aurora.boober.contracts

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath
import io.restassured.module.mockmvc.RestAssuredMockMvc
import no.skatteetaten.aurora.boober.configureObjectMapper
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.io.File

class ContractResponses(val jsonResponses: Map<String, DocumentContext>) {
    inline fun <reified T : Any> response(responseName: String = jsonResponses.keys.first()): T {
        val json = jsonResponses[responseName]?.jsonString()
            ?: throw IllegalArgumentException("Invalid response name,  $responseName")
        return configureObjectMapper(ObjectMapper()).readValue(json)
    }
}

fun withContractResponses(baseTestObject: Any, fn: (responses: ContractResponses) -> Any) {
    val responses = readJsonFiles(baseTestObject)
    val controller = fn(responses)
    setupMockMvc(controller)
}

private fun readJsonFiles(baseTestObject: Any): ContractResponses {
    val baseName =
        baseTestObject::class.simpleName
            ?: throw IllegalArgumentException("Invalid base object, ${baseTestObject::class.simpleName}")
    val folderName = "/contracts/${baseName.toLowerCase().removeSuffix("test")}/responses"
    val content = baseTestObject::class.java.getResource(folderName)

    val files = File(content.toURI()).walk().filter { it.name.endsWith(".json") }.toList()
    val jsonResponses = files.associateBy({ it.name.removeSuffix(".json") }, { JsonPath.parse(it) })
    return ContractResponses(jsonResponses)
}

private fun setupMockMvc(controller: Any) {
    val converter = MappingJackson2HttpMessageConverter().apply {
        objectMapper = configureObjectMapper(ObjectMapper())
    }
    RestAssuredMockMvc.standaloneSetup(MockMvcBuilders.standaloneSetup(controller).setMessageConverters(converter))
}
