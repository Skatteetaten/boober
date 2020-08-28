package no.skatteetaten.aurora.boober.feature

import no.skatteetaten.aurora.boober.model.AuroraConfigException
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.utils.ConditionalOnPropertyMissingOrEmpty
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.durationString
import no.skatteetaten.aurora.boober.utils.filterNullValues
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

val WEBSEAL_ROLES_ANNOTATION: String = "marjory.sits.no/route.roles"
val WEBSEAL_DONE_ANNOTATION: String = "marjory.sits.no-routes-config.done"

@ConditionalOnPropertyMissingOrEmpty("integrations.skap.url")
@Service
class WebsealDisabledFeature : AbstractWebsealFeature("") {

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        cmd: AuroraContextCommand
    ): List<Exception> {
        return adc.featureEnabled("webseal") { field ->
            listOf(AuroraConfigException("Webseal is not supported."))
        } ?: emptyList()
    }
}

@ConditionalOnProperty(value = ["integrations.skap.url"])
@Service
class WebsealFeature(
    @Value("\${boober.webseal.suffix}") suffix: String
) : AbstractWebsealFeature(suffix) {

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {
        return adc.featureEnabled("webseal") { field ->
            val roles = adc.getDelimitedStringOrArrayAsSet("$field/roles", ",")
                .takeIf { it.isNotEmpty() }?.joinToString(",") ?: ""
            val host = adc.getOrNull<String>("$field/host") ?: "${adc.name}-${adc.namespace}"
            val timeout = adc.getOrNull<String>("$field/clusterTimeout")?.let {
                it.toIntOrNull()?.let { n -> "${n}s" } ?: it
            }

            val annotations = mapOf(
                "marjory.sits.no/isOpen" to "false",
                WEBSEAL_ROLES_ANNOTATION to roles,
                "haproxy.router.openshift.io/timeout" to timeout
            ).filterNullValues()

            val routeName = "${adc.name}-webseal"

            val auroraRoute = Route(
                objectName = routeName,
                host = host,
                annotations = annotations
            )

            setOf(auroraRoute.generateOpenShiftRoute(adc.namespace, adc.name, webSealSuffix).generateAuroraResource())
        } ?: emptySet()
    }
}

abstract class AbstractWebsealFeature(
    val webSealSuffix: String
) : Feature {

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return !header.isJob
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler(
                "webseal",
                defaultValue = false,
                validator = { it.boolean() },
                canBeSimplifiedConfig = true
            ),
            AuroraConfigFieldHandler("webseal/clusterTimeout", validator = { it.durationString() }),
            AuroraConfigFieldHandler("webseal/host"),
            AuroraConfigFieldHandler("webseal/strict", validator = { it.boolean() }),
            AuroraConfigFieldHandler("webseal/roles")
        )
    }

    fun willCreateResource(adc: AuroraDeploymentSpec): Boolean {
        return adc.featureEnabled("webseal") {
            true
        } ?: false
    }

    fun shouldWarnAboutFeature(adc: AuroraDeploymentSpec): Boolean = adc.getOrNull("webseal/strict") ?: true
}
