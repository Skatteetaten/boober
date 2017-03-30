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

            if (endpointKey == "projects") {
                return OpenShiftApiUrls(
                        update = "$baseUrl/oapi/v1/projects",
                        get = "$baseUrl/oapi/v1/projects/$name"
                )
            }

            val prefix = if (endpointKey in listOf("services", "configmaps")) {
                "/api"
            } else {
                "/oapi"
            }

            return OpenShiftApiUrls(
                    update = "$baseUrl/$prefix/v1/namespaces/$namespace/$endpointKey",
                    get = "$baseUrl/$prefix/v1/namespaces/$namespace/$endpointKey/$name"
            )
        }
    }
}