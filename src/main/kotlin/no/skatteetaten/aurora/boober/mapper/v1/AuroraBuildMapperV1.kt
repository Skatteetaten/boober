package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraBuild
import no.skatteetaten.aurora.boober.model.AuroraConfigFile

class AuroraBuildMapperV1(
    val name: String,
    applicationFiles: List<AuroraConfigFile>
) {

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
        AuroraConfigFieldHandler("builder/version", defaultValue = "1")
    )
}