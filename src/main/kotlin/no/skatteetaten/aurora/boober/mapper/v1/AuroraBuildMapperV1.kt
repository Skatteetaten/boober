package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraBuild
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.utils.length
import no.skatteetaten.aurora.boober.utils.notBlank

class AuroraBuildMapperV1(val name: String) {

    fun build(auroraDeploymentSpec: AuroraDeploymentSpec): AuroraBuild {

        val type: TemplateType = auroraDeploymentSpec.extract("type")
        val name: String = auroraDeploymentSpec.extract("name")

        val groupId: String = auroraDeploymentSpec.extract("groupId")
        val artifactId: String = auroraDeploymentSpec.extract("artifactId")
        val version: String = auroraDeploymentSpec.extract("version")
        val testGitUrl: String? = auroraDeploymentSpec.extractOrNull("test/gitUrl")

        val skipTriggers = type == TemplateType.development || version.contains("SNAPSHOT") || testGitUrl != null

        val outputKind = if (type == TemplateType.build) {
            "DockerImage"
        } else {
            "ImageStreamTag"
        }

        val outputName = if (type == TemplateType.build) {
            val dockerGroup = groupId.replace(".", "_")
            "$dockerGroup/$artifactId:default"
        } else {
            "$name:latest"
        }

        return AuroraBuild(
            applicationPlatform = auroraDeploymentSpec.extract("applicationPlatform"),
            testGitUrl = testGitUrl,
            testTag = auroraDeploymentSpec.extractOrNull("test/tag"),
            baseName = auroraDeploymentSpec.extract("baseImage/name"),
            baseVersion = auroraDeploymentSpec.extract("baseImage/version"),
            builderName = auroraDeploymentSpec.extract("builder/name"),
            builderVersion = auroraDeploymentSpec.extract("builder/version"),
            extraTags = auroraDeploymentSpec.extract("extraTags"),
            version = version,
            groupId = groupId,
            artifactId = artifactId,
            outputKind = outputKind,
            outputName = outputName,
            triggers = !skipTriggers,
            buildSuffix = auroraDeploymentSpec.extractOrNull("buildSuffix")
        )
    }

    val handlers = listOf(
        AuroraConfigFieldHandler("extraTags", defaultValue = "latest,major,minor,patch"),
        AuroraConfigFieldHandler("buildSuffix"),
        AuroraConfigFieldHandler("builder/name", defaultValue = "architect"),
        AuroraConfigFieldHandler("builder/version", defaultValue = "1"),
        AuroraConfigFieldHandler("baseImage/name"),
        AuroraConfigFieldHandler("baseImage/version"),
        AuroraConfigFieldHandler("test/gitUrl"),
        AuroraConfigFieldHandler("test/tag"),
        AuroraConfigFieldHandler(
            "groupId",
            validator = { it.length(200, "GroupId must be set and be shorter then 200 characters") }),
        AuroraConfigFieldHandler("artifactId",
            defaultValue = name,
            defaultSource = "fileName",
            validator = { it.length(50, "ArtifactId must be set and be shorter then 50 characters", false) }),
        AuroraConfigFieldHandler("version", validator = { it.notBlank("Version must be set") })
    )
}