package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraBuild
import no.skatteetaten.aurora.boober.utils.length
import no.skatteetaten.aurora.boober.utils.notBlank

class AuroraBuildMapperV1(val name: String) {

    fun build(auroraDeploymentSpec: AuroraDeploymentSpec): AuroraBuild {

        val name: String = auroraDeploymentSpec["name"]
        val groupId: String = auroraDeploymentSpec["groupId"]
        val artifactId: String = auroraDeploymentSpec["artifactId"]
        val version: String = auroraDeploymentSpec["version"]

        return AuroraBuild(
            applicationPlatform = auroraDeploymentSpec["applicationPlatform"],
            baseName = auroraDeploymentSpec["baseImage/name"],
            baseVersion = auroraDeploymentSpec["baseImage/version"],
            builderName = auroraDeploymentSpec["builder/name"],
            builderVersion = auroraDeploymentSpec["builder/version"],
            version = version,
            groupId = groupId,
            artifactId = artifactId,
            outputKind = "ImageStreamTag",
            outputName = "$name:latest"
        )
    }

    val handlers = listOf(
        AuroraConfigFieldHandler("builder/name", defaultValue = "architect"),
        AuroraConfigFieldHandler("builder/version", defaultValue = "1"),
        AuroraConfigFieldHandler("baseImage/name"),
        AuroraConfigFieldHandler("baseImage/version"),
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