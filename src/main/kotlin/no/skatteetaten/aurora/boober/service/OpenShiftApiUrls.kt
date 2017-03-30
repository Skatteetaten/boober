package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode

class OpenShiftApiUrls(
        val update: String,
        val get: String
) {
    companion object Factory {
        fun createUrlsForResource(baseUrl: String, namespace: String, json: JsonNode): OpenShiftApiUrls {

            val kind = json.get("kind")?.asText() ?: throw IllegalArgumentException("kind not specified for resource")
            val name = json.get("metadata")?.get("name")?.asText() ?: throw IllegalArgumentException("name not specified for resource")

            return createOpenShiftApiUrls(baseUrl, kind, namespace, name)
        }

        fun createOpenShiftApiUrls(baseUrl: String, kind: String, namespace: String, name: String): OpenShiftApiUrls {

            val endpointKey = kind.toLowerCase() + "s"

            val apiType = if (endpointKey in listOf("services", "configmaps")) "api" else "oapi"
            val namespacePrefix = if (endpointKey !in listOf("projects")) "/namespaces/$namespace" else ""

            val resourceBasePath = "$baseUrl/$apiType/v1$namespacePrefix"

            return OpenShiftApiUrls(
                    update = resourceBasePath,
                    get = "$resourceBasePath/$name"
            )
        }
    }
}