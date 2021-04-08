package no.skatteetaten.aurora.boober.feature

import io.fabric8.kubernetes.api.model.HasMetadata
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.AuroraResourceSource
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.durationString
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.pattern

class FeatureKeyMissingException(
    key: String,
    keys: Set<String>
) : RuntimeException("The feature context key=$key was not found in the context. keys=$keys")

class FeatureWrongTypeException(
    key: String,
    throwable: Throwable
) : RuntimeException("The feature context key=$key was not the expected type ${throwable.localizedMessage}", throwable)

typealias FeatureContext = Map<String, Any>

@Suppress("UNCHECKED_CAST")
inline fun <reified T> FeatureContext.getContextKey(key: String): T {

    val value = try {
        this[key] as T?
    } catch (e: Exception) {
        throw FeatureWrongTypeException(key, e)
    }
    return value ?: throw FeatureKeyMissingException(key, this.keys)
}

interface Feature {

    fun List<HasMetadata>.generateAuroraResources() = this.map { it.generateAuroraResource() }
    fun HasMetadata.generateAuroraResource(header: Boolean = false) = generateResource(this, header)

    fun generateResource(content: HasMetadata, header: Boolean = false) =
        AuroraResource(content, AuroraResourceSource(this::class.java), header = header)

    fun modifyResource(resource: AuroraResource, comment: String) =
        resource.sources.add(AuroraResourceSource(this::class.java, comment = comment))

    /*
      Should this feature run or not.

      You can either do this via Spring Conditional annotations to react to the environment,
      or you can react on the header and toggle if you are active in that way.

      If you look at BuildFeature you will see that it reacts on the Application.type to only enable
      itself if the type is development

     */
    fun enable(header: AuroraDeploymentSpec): Boolean = true

    /*
      Return a set of Handlers, see AuroraConfigFieldHandler for details on what a handler is
     */
    fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler>

    /*
      Method to create a context for the given feature

      This context will be sent to validate/generate/modify steps

      The validationContext flag will let the  the context know if the context should only be used for validation

      You can throw an exception here and it will be registered as a validation error if you like
     */
    fun createContext(spec: AuroraDeploymentSpec, cmd: AuroraContextCommand, validationContext: Boolean): FeatureContext = emptyMap()

    /*
    Perform validation of this feature.

    If this method throws it will be handled as a single error or multiple errors if ExceptionList
    */
    fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> =
        emptyList()

    /*
       Generate a set of AuroraResource from this feature

       Resource generation of all features are run before the modify step occurs

       If this method throws errors other features will still be run.

       If any feature has thrown an error the process will stop

       use the generateResource method in this interface as a helper to add the correct source

       If you have more then one error throw an ExceptionList
    */
    fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> = emptySet()

    /*
        Modify generated resources

        Resource modification of all features are run before the validate step occurs

        If this method throws errors other features will still modify the resources.

        If any feature has thrown an error the process will stop

        use the modifyResource method in this interface as a helper to add a source to your modification

        If you have more then one error throw an ExceptionList
     */
    fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: FeatureContext
    ) = Unit
}

enum class ApplicationPlatform(val baseImageName: String, val baseImageVersion: Int, val insecurePolicy: String) {
    java("wingnut8", 1, "None"),
    python("rumple39", 1, "None"),
    web("wrench8", 1, "Redirect");
}

enum class TemplateType(
    val versionAndGroupRequired: Boolean = true,
    val auroraGeneratedDeployment: Boolean = true
) {
    cronjob(auroraGeneratedDeployment = false),
    job(auroraGeneratedDeployment = false),
    deploy,
    development,
    localTemplate(false, false),
    template(false, false)
}

enum class DeploymentState {
    deploymentConfig,
    deployment
}

val AuroraDeploymentSpec.applicationPlatform: ApplicationPlatform get() = this["applicationPlatform"]

val ApplicationDeploymentRef.headerHandlers: Set<AuroraConfigFieldHandler>
    get() {

        val validSchemaVersions = listOf("v1")

        val envNamePattern = "^[a-z0-9\\-]{0,52}$"
        val envNameMessage =
            "Environment must consist of lower case alphanumeric characters or '-'. It must be no longer than 52 characters."

        return setOf(
            AuroraConfigFieldHandler(
                "schemaVersion",
                validator = { it.oneOf(validSchemaVersions) }),
            AuroraConfigFieldHandler(
                "type",
                validator = { node -> node.oneOf(TemplateType.values().map { it.toString() }) }),

            // The value for jobs here will be wrong, but we do not use deployState for jobs.
            AuroraConfigFieldHandler(
                "deployState",
                defaultValue = "deploymentConfig",
                validator = { node -> node.oneOf(DeploymentState.values().map { it.toString() }) }),
            AuroraConfigFieldHandler(
                "applicationPlatform",
                defaultValue = "java",
                validator = { node -> node.oneOf(ApplicationPlatform.values().map { it.toString() }) }),
            AuroraConfigFieldHandler("affiliation", validator = {
                it.pattern(
                    "^[a-z]{1,10}$",
                    "Affiliation can only contain letters and must be no longer than 10 characters"
                )
            }),
            AuroraConfigFieldHandler("segment"),
            AuroraConfigFieldHandler(
                "cluster",
                validator = { it.notBlank("Cluster must be set") }),
            AuroraConfigFieldHandler("permissions/admin"),
            AuroraConfigFieldHandler("permissions/view"),
            AuroraConfigFieldHandler("permissions/adminServiceAccount"),
            // Max length of OpenShift project names is 63 characters. Project name = affiliation + "-" + envName.
            AuroraConfigFieldHandler(
                "envName", validator = { it.pattern(envNamePattern, envNameMessage) },
                defaultSource = "folderName",
                defaultValue = this.environment
            ),
            AuroraConfigFieldHandler("name",
                defaultValue = this.application,
                defaultSource = "fileName",
                validator = {
                    it.pattern(
                        "^[a-z][-a-z0-9]{0,38}[a-z0-9]$",
                        "Name must be alphanumeric and no more than 40 characters",
                        false
                    )
                }),
            AuroraConfigFieldHandler(
                "env/name",
                validator = { it.pattern(envNamePattern, envNameMessage, false) }),
            AuroraConfigFieldHandler("env/ttl", validator = { it.durationString() }),
            AuroraConfigFieldHandler("env/autoDeploy", validator = { it.boolean() }, defaultValue = false),
            AuroraConfigFieldHandler("baseFile"),
            AuroraConfigFieldHandler("envFile"),
            AuroraConfigFieldHandler("includeEnvFile")

        )
    }
