package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isNotNull
import assertk.assertions.message
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraRoute
import no.skatteetaten.aurora.boober.model.InsecurePolicy
import no.skatteetaten.aurora.boober.model.Route
import no.skatteetaten.aurora.boober.model.SecureRoute
import no.skatteetaten.aurora.boober.model.TlsTermination
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecService
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import org.junit.jupiter.api.Test

class RouteValidationTest : ResourceLoader() {

    val ref = ApplicationDeploymentRef(
        environment = "utv",
        application = "reference"
    )

    @Test
    fun `Should not allow tls route with dot in host`() {
        val routes = AuroraRoute(
            route = listOf(
                Route(
                    objectName = "ref1",
                    host = "test.aurora",
                    tls = SecureRoute(InsecurePolicy.None, TlsTermination.edge)
                )
            )
        )
        assertThat {
            AuroraDeploymentSpecService.validateRoutes(routes, ref)
        }.isNotNull().isFailure()
            .hasMessage("Application reference in environment utv have a tls enabled route with a '.' in the host. Route name=ref1 with tls uses '.' in host name.")
    }

    @Test
    fun `Should not allow two routes with the same name`() {

        val routes = AuroraRoute(
            route = listOf(
                Route(objectName = "ref1", host = "test-aurora"),
                Route(objectName = "ref1", host = "test-aurora2"),
                Route(objectName = "ref2", host = "test-aurora3"),
                Route(objectName = "ref2", host = "test-aurora4")
            )
        )

        assertThat {
            AuroraDeploymentSpecService.validateRoutes(routes, ref)
        }.isNotNull().isFailure()
            .message()
            .isEqualTo("Application reference in environment utv have routes with duplicate names. Route name=ref1 is duplicated, Route name=ref2 is duplicated.")
    }

    @Test
    fun `Should not allow two routes with the same host`() {

        val routes = AuroraRoute(
            route = listOf(
                Route(objectName = "ref1", host = "test-aurora"),
                Route(objectName = "ref2", host = "test-aurora")
            )
        )

        assertThat {
            AuroraDeploymentSpecService.validateRoutes(routes, ref)
        }.isNotNull().isFailure()
            .message()
            .isEqualTo("Application reference in environment utv have duplicated targets. target=test-aurora is duplicated in routes ref1,ref2.")
    }

    @Test
    fun `Should not allow two routes with the same host and path`() {

        val routes = AuroraRoute(
            route = listOf(
                Route(objectName = "ref1", host = "test-aurora", path = "/aurora"),
                Route(objectName = "ref2", host = "test-aurora", path = "/aurora")
            )
        )

        assertThat {
            AuroraDeploymentSpecService.validateRoutes(routes, ref)
        }.isNotNull().isFailure()
            .message().isEqualTo("Application reference in environment utv have duplicated targets. target=test-aurora/aurora is duplicated in routes ref1,ref2.")
    }
}