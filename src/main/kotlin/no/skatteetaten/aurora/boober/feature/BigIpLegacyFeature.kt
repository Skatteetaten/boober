package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.newObjectMeta
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.openshift.BigIp
import no.skatteetaten.aurora.boober.model.openshift.BigIpKonfigurasjonstjenesten
import no.skatteetaten.aurora.boober.model.openshift.BigIpSpec
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.apache.commons.codec.digest.DigestUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class BigIpLegacyFeature(
    @Value("\${boober.route.suffix}") val routeSuffix: String,
    val bigIpFeature: BigIpFeature
) : Feature {

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return bigIpFeature.enable(header)
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return bigIpFeature.handlers(header, cmd)
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {
        return bigIpFeature.validate(adc, fullValidation, context)
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        val enabled = adc.isFeatureEnabled()
        if (!enabled) {
            return emptySet()
        }

        val routeName = "${adc.name}-bigip"

        // dette var den gamle applicationDeploymentId som må nå være hostname
        val routeHost = DigestUtils.sha1Hex("${adc.namespace}/${adc.name}")

        val auroraRoute = Route(
            objectName = routeName,
            host = routeHost,
            annotations = adc.getRouteAnnotations("bigip/routeAnnotations/").addIfNotNull("bigipRoute" to "true")
        )

        val bigIp = BigIp(
            _metadata = newObjectMeta {
                name = adc.name
                namespace = adc.namespace
            },
            spec = BigIpSpec(
                routeName, BigIpKonfigurasjonstjenesten(
                    service = adc["bigip/service"],
                    asmPolicy = adc.getOrNull("bigip/asmPolicy"),
                    oauthScopes = adc.getDelimitedStringOrArrayAsSetOrNull("bigip/oauthScopes"),
                    apiPaths = adc.getDelimitedStringOrArrayAsSetOrNull("bigip/apiPaths"),
                    externalHost = adc.getOrNull("bigip/externalHost")
                )
            )
        )

        return setOf(
            auroraRoute.generateOpenShiftRoute(adc.namespace, adc.name, routeSuffix).generateAuroraResource(),
            bigIp.generateAuroraResource()
        )
    }

    private fun AuroraDeploymentSpec.isFeatureEnabled(): Boolean {
        val hasSubkeys = this.hasSubKeys("bigip")
        return getOrNull<Boolean>("bigip/enabled") ?: hasSubkeys
    }

    fun fetchExternalHostsAndPaths(adc: AuroraDeploymentSpec): List<String> {
        val enabled = adc.isFeatureEnabled()
        if (!enabled) return emptyList()
        val host: String = adc.getOrNull("bigip/externalHost") ?: return emptyList()
        return adc.getDelimitedStringOrArrayAsSet("bigip/apiPaths").map {
            "$host$it"
        }
    }
}
