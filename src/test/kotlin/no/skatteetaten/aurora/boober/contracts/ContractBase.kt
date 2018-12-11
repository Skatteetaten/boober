package no.skatteetaten.aurora.boober.contracts

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath
import io.restassured.module.mockmvc.RestAssuredMockMvc
import no.skatteetaten.aurora.boober.configureObjectMapper
import org.bouncycastle.asn1.x500.style.RFC4519Style.name
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.io.File

class ContractResponses(val jsonResponses: Map<String, DocumentContext>) {
    inline fun <reified T : Any> response(responseName: String): T {
        val json =
            jsonResponses[responseName]?.jsonString() ?: throw IllegalArgumentException("Invalid response name,  $name")
        return configureObjectMapper(ObjectMapper()).readValue(json)
    }
}

inline fun <reified T : Any> contractResponses(fn: (responses: ContractResponses) -> Any) {
    val baseName =
        T::class.simpleName ?: throw IllegalArgumentException("Invalid base object, ${T::class.simpleName}")
    val folderName = "/contracts/${baseName.toLowerCase().removeSuffix("base")}/responses"
    val content = T::class.java.getResource(folderName)

    val files = File(content.toURI()).walk().filter { it.name.endsWith(".json") }.toList()
    val jsonResponses = files.associateBy({ it.name.removeSuffix(".json") }, { JsonPath.parse(it) })

    val controller = fn(ContractResponses(jsonResponses))
    setupMockMvc(controller)
}

fun setupMockMvc(controller: Any) {
    val converter = MappingJackson2HttpMessageConverter().apply {
        objectMapper = configureObjectMapper(ObjectMapper())
    }
    RestAssuredMockMvc.standaloneSetup(MockMvcBuilders.standaloneSetup(controller).setMessageConverters(converter))
}
