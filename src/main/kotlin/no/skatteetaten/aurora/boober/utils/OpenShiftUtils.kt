package no.skatteetaten.aurora.boober.utils

val kubernetesNonApiGroupResources = setOf(
    "namespace", "service", "configmap", "secret", "serviceaccount",
    "replicationcontroller", "persistentvolumeclaim", "pod"
)

/**
 * These resources have been pre applied during the generate step in a Feature. They are missing common labels and
 * ownerreference since they have been applied before the ApplicationDeployment resource. So when we are creating an
 * openshift command we need to get the existing resource and create an update command if not the command created will
 * be of type CREATED and that will result in a 409-Conflict since it's already created/applied.
 */
val preAppliedResources = setOf(
    "storagegridobjectarea"
)

val nonGettableResources = setOf(
    "processedtemplate", "deploymentrequest", "imagestreamimport", "job"
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
        if (kind.lowercase() in kubernetesNonApiGroupResources) {
            "api"
        } else {
            "oapi"
        }
    } else {
        "apis"
    }

fun findApiVersion(kind: String): String =
    apiGroups
        .filter { it.value.contains(kind.lowercase()) }
        .map { it.key }
        .firstOrNull() ?: "v1"

val apiGroups: Map<String, List<String>> =
    mapOf(
        "skatteetaten.no/v1" to listOf("applicationdeployment", "bigip", "auroracname", "auroraazureapp"),
        "apps.openshift.io/v1" to listOf("deploymentconfig", "deploymentrequest"),
        "apps/v1" to listOf("deployment"),
        "route.openshift.io/v1" to listOf("route"),
        "user.openshift.io/v1" to listOf("user", "group"),
        "batch/v1" to listOf("job"),
        "batch/v1beta1" to listOf("cronjob"),
        "project.openshift.io/v1" to listOf("project", "projectrequest"),
        "template.openshift.io/v1" to listOf("template"),
        "image.openshift.io/v1" to listOf("imagestream", "imagestreamtag", "imagestreamimport"),
        "authorization.openshift.io/v1" to listOf("rolebinding"),
        "build.openshift.io/v1" to listOf("buildconfig")

    )
