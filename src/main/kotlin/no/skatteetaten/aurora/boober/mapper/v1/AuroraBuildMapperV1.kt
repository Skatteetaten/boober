package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.ApplicationPlatform
import no.skatteetaten.aurora.boober.model.ApplicationPlatform.java
import no.skatteetaten.aurora.boober.model.ApplicationPlatform.web
import no.skatteetaten.aurora.boober.model.AuroraBuild
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.utils.length
import no.skatteetaten.aurora.boober.utils.notBlank

class AuroraBuildMapperV1 {

    fun build(auroraConfigFields: AuroraConfigFields, dockerRegistry: String): AuroraBuild {

        val type = auroraConfigFields.extract("type", { TemplateType.valueOf(it.textValue()) })
        val applicationPlatform = auroraConfigFields.extract("applicationPlatform", { ApplicationPlatform.valueOf(it.textValue()) })
        val name = auroraConfigFields.extract("name")

        val groupId = auroraConfigFields.extract("groupId")
        val artifactId = auroraConfigFields.extract("artifactId")
        val version = auroraConfigFields.extract("version")
        val testGitUrl = auroraConfigFields.extractOrNull("test/gitUrl")

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

        return when (applicationPlatform) {
            java -> AuroraBuild(
                    testJenkinsfile = auroraConfigFields.extract("test/jenkinsfile"),
                    testGitUrl = testGitUrl,
                    testTag = auroraConfigFields.extractOrNull("test/tag"),
                    baseName = auroraConfigFields.extractOrNull("baseImage/name") ?: "oracle8",
                    baseVersion = auroraConfigFields.extractOrNull("baseImage/version") ?: "1",
                    builderName = auroraConfigFields.extractOrNull("builder/name") ?: "leveransepakkebygger",
                    builderVersion = auroraConfigFields.extractOrNull("builder/version") ?: "prod",
                    extraTags = auroraConfigFields.extract("extraTags"),
                    version = version,
                    groupId = groupId,
                    artifactId = artifactId,
                    outputKind = outputKind,
                    outputName = outputName,
                    triggers = !skipTriggers,
                    buildSuffix = auroraConfigFields.extractOrNull("buildSuffix")
            )
            web -> AuroraBuild(
                    testJenkinsfile = auroraConfigFields.extract("test/jenkinsfile"),
                    testGitUrl = testGitUrl,
                    testTag = auroraConfigFields.extractOrNull("test/tag"),
                    baseName = auroraConfigFields.extractOrNull("baseImage/name") ?: "wrench",
                    baseVersion = auroraConfigFields.extractOrNull("baseImage/version") ?: "0",
                    builderName = auroraConfigFields.extractOrNull("builder/name") ?: "architect",
                    builderVersion = auroraConfigFields.extractOrNull("builder/version") ?: "1",
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
    }

    val handlers = listOf(
            AuroraConfigFieldHandler("extraTags", defaultValue = "latest,major,minor,patch"),
            AuroraConfigFieldHandler("buildSuffix"),
            AuroraConfigFieldHandler("builder/version"),
            AuroraConfigFieldHandler("builder/name"),
            AuroraConfigFieldHandler("baseImage/name"),
            AuroraConfigFieldHandler("baseImage/version"),
            AuroraConfigFieldHandler("test/gitUrl"),
            AuroraConfigFieldHandler("test/jenkinsfile", defaultValue = "test.Jenkinsfile"),
            AuroraConfigFieldHandler("test/tag"),
            AuroraConfigFieldHandler("groupId", validator = { it.length(200, "GroupId must be set and be shorter then 200 characters") }),
            AuroraConfigFieldHandler("artifactId", validator = { it.length(50, "ArtifactId must be set and be shorter then 50 characters") }),
            AuroraConfigFieldHandler("version", validator = { it.notBlank("Version must be set") })
    )


}