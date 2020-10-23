package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.newObjectMeta
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.openshift.BigIp
import no.skatteetaten.aurora.boober.model.openshift.BigIpKonfigurasjonstjenesten
import no.skatteetaten.aurora.boober.model.openshift.BigIpSpec
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.apache.commons.codec.digest.DigestUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class BigIpFeature(
    @Value("\${boober.route.suffix}") val routeSuffix: String
) : Feature {

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return !header.isJob
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler("bigip/service"),
            AuroraConfigFieldHandler("bigip/asmPolicy"),
            AuroraConfigFieldHandler("bigip/externalHost"),
            AuroraConfigFieldHandler("bigip/oauthScopes"),
            AuroraConfigFieldHandler("bigip/apiPaths")
        ) + findRouteAnnotationHandlers("bigip", cmd.applicationFiles, "routeAnnotations")
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        cmd: AuroraContextCommand
    ): List<Exception> {
        if (adc.hasSubKeys("bigip") && adc.getOrNull<String>("bigip/service").isNullOrEmpty()) {
            throw AuroraDeploymentSpecValidationException("bigip/service is required if any other bigip flags are set")
        }
        return emptyList()
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {

        if (!adc.hasSubKeys("bigip")) {
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
}
