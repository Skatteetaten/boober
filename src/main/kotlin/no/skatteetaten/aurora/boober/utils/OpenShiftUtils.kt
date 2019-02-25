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
        "skatteetaten.no/v1" to listOf("applicationdeployment"),
        "apps.openshift.io/v1" to listOf("deploymentconfig", "deploymentrequest"),
        "route.openshift.io/v1" to listOf("route"),
        "user.openshift.io/v1" to listOf("user", "group"),
        "project.openshift.io/v1" to listOf("project", "projectrequest"),
        "template.openshift.io/v1" to listOf("template"),
        "images.openshift.io/v1" to listOf("imagestream", "imagestreamtag", "imagestreamimport"),
        "authorization.openshift.io/v1" to listOf("rolebinding"),
        "build.openshift.io/v1" to listOf("buildconfig")

    )
