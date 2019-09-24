package no.skatteetaten.aurora.boober.feature

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.service.Feature

class ApplicationDeploymentFeature() : Feature{

    //message, ttl
    override fun handlers(header: AuroraDeploymentContext): Set<AuroraConfigFieldHandler> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}