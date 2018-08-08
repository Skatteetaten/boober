package no.skatteetaten.aurora.boober.service.openshift

class OpenShiftApiUrls(
    val create: String,
    val get: String? = null,
    val update: String? = null
) {
    companion object Factory {

        @JvmStatic
        @JvmOverloads
        fun createOpenShiftApiUrls(
            baseUrl: String,
            kind: String,
            namespace: String? = null,
            name: String?
        ): OpenShiftApiUrls {

            if (kind == "application") {
                val createUrl = "$baseUrl/apis/skatteetaten.no/v1/namespaces/$namespace/applications"
                return OpenShiftApiUrls(
                    create = createUrl,
                    get = "$createUrl/$name",
                    update = "$createUrl/$name"
                )
            }
            if (kind == "processedtemplate") {
                val bcBaseUrl = getCollectionPathForResource(baseUrl, "processedtemplate", namespace)
                return OpenShiftApiUrls(
                    create = "$bcBaseUrl"
                )
            }
            if (kind == "deploymentrequest") {
                val dcBaseUrl = getCollectionPathForResource(baseUrl, "deploymentconfig", namespace)
                return OpenShiftApiUrls(
                    create = "$dcBaseUrl/$name/instantiate"
                )
            }
            val createUrl = getCollectionPathForResource(baseUrl, kind, namespace)

            return OpenShiftApiUrls(
                create = createUrl,
                get = "$createUrl/$name",
                update = "$createUrl/$name"
            )
        }

        @JvmStatic
        @JvmOverloads
        fun getCollectionPathForResource(baseUrl: String, kind: String, namespace: String? = null): String {
            val endpointKey = kind.toLowerCase() + "s"

            val apiType = getApiType(endpointKey)
            val namespacePrefix = if (endpointKey !in listOf(
                    "namespaces",
                    "projects",
                    "projectrequests",
                    "deploymentreqeusts",
                    "users",
                    "groups"
                )
            ) {
                namespace ?: throw IllegalArgumentException("namespace required for resource kind $kind")
                "/namespaces/$namespace"
            } else ""

            val resourceBasePath = "$baseUrl/$apiType/v1$namespacePrefix/$endpointKey"
            return resourceBasePath
        }

        fun getApiType(endpointKey: String): String {
            return if (endpointKey in listOf(
                    "namespaces", "services", "configmaps", "secrets", "serviceaccounts",
                    "replicationcontrollers", "persistentvolumeclaims", "pods"
                )
            ) "api" else "oapi"
        }
    }
}