package no.skatteetaten.aurora.boober.feature

import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.utils.durationString
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.pattern
import no.skatteetaten.aurora.boober.utils.startsWith

interface Feature {

    fun enable(header: AuroraDeploymentSpec): Boolean = true
    fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler>
    fun validate(adc: AuroraDeploymentSpec, fullValidation: Boolean, cmd: AuroraContextCommand): List<Exception> =
        emptyList()

    fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> = emptySet()
    fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) = Unit
}

enum class ApplicationPlatform(val baseImageName: String, val baseImageVersion: Int, val insecurePolicy: String) {
    java("wingnut8", 1, "None"),
    web("wrench8", 1, "Redirect")
}

enum class TemplateType(val versionRequired: Boolean) {
    deploy(true), development(true), localTemplate(false), template(false)
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
            AuroraConfigFieldHandler("baseFile"),
            AuroraConfigFieldHandler("envFile", validator = {
                it?.startsWith("about-", "envFile must start with about")
            })
        )
    }
