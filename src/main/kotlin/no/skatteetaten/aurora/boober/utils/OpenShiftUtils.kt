package no.skatteetaten.aurora.boober.utils

val kubernetesNonApiGroupResources = setOf(
    "namespace", "service", "configmap", "secret", "serviceaccount",
    "replicationcontroller", "persistentvolumeclaim", "pod"
)

val nonGettableResources = setOf(
    "processedtemplate", "deploymentrequest", "imagestreamimport"
)

val kindsWithoutNamespace = listOf(
    "namespaces",
    "projects",
    "projectrequest",
    "deploymentrequest",
    "users",
    "groups"
)

fun findOpenShiftApiPrefix(apiVersion: String, kind: String) =
    if (apiVersion == "v1") {
        if (kind.toLowerCase() in kubernetesNonApiGroupResources) {
            "api"
        } else {
            "oapi"
        }
    } else {
        "apis"
    }

fun findApiVersion(kind: String): String =
    apiGroups
        .filter { it.value.contains(kind.toLowerCase()) }
        .map { it.key }
        .firstOrNull() ?: "v1"

val apiGroups: Map<String, List<String>> =
    mapOf(
        "apps/v1" to listOf("deployment"),
        "skatteetaten.no/v1" to listOf("applicationdeployment")
    )
