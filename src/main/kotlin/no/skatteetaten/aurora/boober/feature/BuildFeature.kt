package no.skatteetaten.aurora.boober.feature

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.service.internal.BuildConfigGenerator

class BuildFeature : Feature{
    override fun handlers(header: AuroraDeploymentSpec, adr: ApplicationDeploymentRef, files: List<AuroraConfigFile>): Set<AuroraConfigFieldHandler> = setOf(
                AuroraConfigFieldHandler("builder/name", defaultValue = "architect"),
                AuroraConfigFieldHandler("builder/version", defaultValue = "1")
        )

}