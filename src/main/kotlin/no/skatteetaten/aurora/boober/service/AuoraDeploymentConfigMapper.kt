package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraConfigExtractor
import no.skatteetaten.aurora.boober.model.AuroraConfigField
import no.skatteetaten.aurora.boober.utils.length
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.required

interface AuroraDeploymentConfigMapper {

    fun validate(fields: Map<String, AuroraConfigField>)

    val extractors: List<AuroraConfigExtractor>
}

class AuroraDeploymentConfigMapperV1 : AuroraDeploymentConfigMapper {

    override val extractors = listOf(
            AuroraConfigExtractor("type", "/type", { it.required("Type is required") }),
            AuroraConfigExtractor("artifactId", "/artifactId", { it.length(50, "ArtifactId must be set and be shorter then 50 characters") }),
            AuroraConfigExtractor("version", "/version", { it.notBlank("Version must be set") }),
            AuroraConfigExtractor("groupId", "/groupIp", { it.length(200, "GroupId must be set and be shorter then 200 characters") }),
            AuroraConfigExtractor("name", "/name"),
            AuroraConfigExtractor("cluster", "/cluster", { it.notBlank("Cluster must be set") })
    )

    //TODO: all all other fieldextractors
    override fun validate(fields: Map<String, AuroraConfigField>) {

    }


}
//TODO: implement validation rules below
/*

class AuroraConfigRequiredV1(val config: Map<String, Any?>?, val build: Map<String, Any?>?) {

    @get:NotNull
    @get:Pattern(message = "Only lowercase letters, max 24 length", regexp = "^[a-z]{0,23}[a-z]$")
    val affiliation
        get() = config?.s("affiliation")

    @get:NotNull
    val cluster
        get() = config?.s("cluster")

    @get:NotNull
    val type
        get() = config?.s("type")?.let { TemplateType.valueOf(it) }

    @get:Pattern(message = "Must be valid DNSDNS952 label", regexp = "^[a-z][-a-z0-9]{0,23}[a-z0-9]$")
    val name
        get() = config?.s("name") ?: build?.s("ARTIFACT_ID")

    val envName
        get() = config?.s("envName")

    @get:NotNull
    @get:Size(min = 1, max = 50)
    val artifactId = build?.s("ARTIFACT_ID")

    @get:NotNull
    @get:Size(min = 1, max = 200)
    val groupId = build?.s("GROUP_ID")

    @get:NotNull
    @get:Size(min = 1)
    val version = build?.s("VERSION")
}

 */