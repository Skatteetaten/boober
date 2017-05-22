package no.skatteetaten.aurora.boober.service.openshift

class OpenShiftApiUrls(
        val create: String,
        val get: String? = null,
        val update: String? = null
) {
    companion object Factory {


        @JvmStatic
        @JvmOverloads
        fun createOpenShiftApiUrls(baseUrl: String, kind: String, name: String?, namespace: String? = null): OpenShiftApiUrls {

            if (kind == "processedtemplate") {
                val bcBaseUrl = getCollectionPathForResource(baseUrl, "processedtemplate", namespace)
                return OpenShiftApiUrls(
                        create = "$bcBaseUrl"
                )
            }
            if (kind == "buildrequest") {
                val bcBaseUrl = getCollectionPathForResource(baseUrl, "buildconfig", namespace)
                return OpenShiftApiUrls(
                        create = "$bcBaseUrl/$name/instantiate"
                )
            }
            if (kind == "deploymentrequest") {
                val dcBaseUrl = getCollectionPathForResource(baseUrl, "deploymentconfig", namespace)
                return OpenShiftApiUrls(
                        create = "$dcBaseUrl/$name/instantiate"
                )
            }
            val createUrl = getCollectionPathForResource(baseUrl, kind, namespace)


            val getUrl = if (kind == "projectrequest") {
                // Nasty business; for ProjectRequest we need to use the Project kind when checking if the resource
                // exists. So we need to switch here...
                val collectionPathForProject = getCollectionPathForResource(baseUrl, "project", namespace)
                "$collectionPathForProject/$name"
            } else "$createUrl/$name"

            return OpenShiftApiUrls(
                    create = createUrl,
                    get = getUrl,
                    update = "$createUrl/$name"
            )
        }

        fun getCollectionPathForResource(baseUrl: String, kind: String, namespace: String? = null): String {
            val endpointKey = kind.toLowerCase() + "s"

            val apiType = if (endpointKey in listOf("services", "configmaps", "secrets", "replicationcontrollers", "persistentvolumeclaims", "pods")) "api" else "oapi"
            val namespacePrefix = if (endpointKey !in listOf("projects", "projectrequests", "buildrequests", "deploymentreqeusts", "users", "groups")) {
                namespace ?: throw IllegalArgumentException("namespace required for resource kind ${kind}")
                "/namespaces/$namespace"
            } else ""

            val resourceBasePath = "$baseUrl/$apiType/v1$namespacePrefix/$endpointKey"
            return resourceBasePath
        }

    }
}