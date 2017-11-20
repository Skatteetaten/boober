package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.ApplicationPlatform
import no.skatteetaten.aurora.boober.model.AuroraBuild
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.utils.length
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.oneOf

class AuroraBuildMapperV1(val applicationId: ApplicationId) {

    fun build(auroraConfigFields: AuroraConfigFields, dockerRegistry: String): AuroraBuild {

        val type: TemplateType = auroraConfigFields.extract("type")
        val applicationPlatform: ApplicationPlatform = auroraConfigFields.extract("applicationPlatform")
        val name: String = auroraConfigFields.extract("name")

        val groupId: String = auroraConfigFields.extract("groupId")
        val artifactId: String = auroraConfigFields.extract("artifactId")
        val version: String = auroraConfigFields.extract("version")
        val testGitUrl: String? = auroraConfigFields.extract("test/gitUrl")

        val skipTriggers = type == TemplateType.development || version.contains("SNAPSHOT") || testGitUrl != null

        val outputKind = if (type == TemplateType.build) {
            "DockerImage"
        } else {
            "ImageStreamTag"
        }

        val outputName = if (type == TemplateType.build) {
            val dockerGroup = groupId.replace(".", "_")
            "$dockerRegistry/$dockerGroup/$artifactId:default"
        } else {
            "$name:latest"
        }


        return AuroraBuild(
                applicationPlatform = auroraConfigFields.extract("applicationPlatform"),
                testGitUrl = testGitUrl,
                testTag = auroraConfigFields.extractOrNull("test/tag"),
                baseName = auroraConfigFields.extractOrNull("baseImage/name") ?: applicationPlatform.baseImageName,
                baseVersion = auroraConfigFields.extractOrNull("baseImage/version") ?: applicationPlatform.baseImageVersion,
                builderName = auroraConfigFields.extract("builder/name"),
                builderVersion = auroraConfigFields.extract("builder/version"),
                extraTags = auroraConfigFields.extract("extraTags"),
                version = version,
                groupId = groupId,
                artifactId = artifactId,
                outputKind = outputKind,
                outputName = outputName,
                triggers = !skipTriggers,
                buildSuffix = auroraConfigFields.extractOrNull("buildSuffix")
        )
    }

    val handlers = listOf(
            AuroraConfigFieldHandler("extraTags", defaultValue = "latest,major,minor,patch"),
            AuroraConfigFieldHandler("applicationPlatform", defaultValue = ApplicationPlatform.java, validator = { it.oneOf(ApplicationPlatform.values().map { it.name }) }),
            AuroraConfigFieldHandler("buildSuffix"),
            AuroraConfigFieldHandler("builder/name", defaultValue = "architect"),
            AuroraConfigFieldHandler("builder/version", defaultValue = "1"),
            AuroraConfigFieldHandler("baseImage/name"),
            AuroraConfigFieldHandler("baseImage/version"),
            AuroraConfigFieldHandler("test/gitUrl"),
            AuroraConfigFieldHandler("test/tag"),
            AuroraConfigFieldHandler("groupId", validator = { it.length(200, "GroupId must be set and be shorter then 200 characters") }),
            AuroraConfigFieldHandler("artifactId",
                    defaultSource = "fileName",
                    defaultValue = applicationId.application, validator = { it.length(50, "ArtifactId must be set and be shorter then 50 characters", false) }),
            AuroraConfigFieldHandler("version", validator = { it.notBlank("Version must be set") })
    )
}