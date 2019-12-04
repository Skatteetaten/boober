package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.newObjectMeta
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.openshift.BigIp
import no.skatteetaten.aurora.boober.model.openshift.BigIpKonfigurasjonstjenesten
import no.skatteetaten.aurora.boober.model.openshift.BigIpSpec
import no.skatteetaten.aurora.boober.utils.notBlank
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class BigIpFeature(
    @Value("\${boober.route.suffix}") val routeSuffix: String
) : Feature {

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler(
                name = "bigip/service",
                validator = {
                    it.notBlank("Service name for BIG-IP is required")
                }
            ),
            AuroraConfigFieldHandler("bigip/asmPolicy"),
            AuroraConfigFieldHandler("bigip/externalHost"),
            AuroraConfigFieldHandler("bigip/oauthScopes"),
            AuroraConfigFieldHandler("bigip/apiPaths")
        ) + findRouteAnnotationHandlers("bigip", cmd.applicationFiles)
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {

        val routeName = "${adc.name}-bigip"

        val auroraRoute = Route(
            objectName = routeName,
            host = adc.applicationDeploymentId,
            annotations = adc.getRouteAnnotations("bigip/annotations/")
        )

        val bigIp = BigIp(
            _metadata =
            newObjectMeta {
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
}
