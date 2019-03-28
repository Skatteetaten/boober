package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraBuild

class AuroraBuildMapperV1(
    val name: String
) {

    fun build(auroraDeploymentSpec: AuroraDeploymentSpec): AuroraBuild {

        val name: String = auroraDeploymentSpec["name"]

        return AuroraBuild(
            applicationPlatform = auroraDeploymentSpec["applicationPlatform"],
            baseName = auroraDeploymentSpec["baseImage/name"],
            baseVersion = auroraDeploymentSpec["baseImage/version"],
            builderName = auroraDeploymentSpec["builder/name"],
            builderVersion = auroraDeploymentSpec["builder/version"],
            version = auroraDeploymentSpec["version"],
            groupId = auroraDeploymentSpec["groupId"],
            artifactId = auroraDeploymentSpec["artifactId"],
            outputKind = "ImageStreamTag",
            outputName = "$name:latest"
        )
    }

    val handlers = listOf(
        AuroraConfigFieldHandler("builder/name", defaultValue = "architect"),
        AuroraConfigFieldHandler("builder/version", defaultValue = "1")
    )
}