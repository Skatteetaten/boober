package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import org.springframework.stereotype.Service
import java.io.StringWriter

@Service
class VelocityTemplateJsonService(val ve: VelocityEngine, val mapper: ObjectMapper) {

    fun renderToJson(template: String, content: Map<String, Any?>): JsonNode {

        val context = VelocityContext().apply {
            content.forEach { put(it.key, it.value) }
        }
        val t = ve.getTemplate("templates/$template.vm")
        val sw = StringWriter()
        t.merge(context, sw)
        val mergedResult = sw.toString()

        return mapper.readTree(mergedResult)
    }
}