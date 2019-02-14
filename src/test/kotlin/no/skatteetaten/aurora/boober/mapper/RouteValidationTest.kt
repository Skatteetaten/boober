package no.skatteetaten.aurora.boober.mapper

import assertk.assert
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.catch
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraRoute
import no.skatteetaten.aurora.boober.model.Route
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecService
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import org.junit.jupiter.api.Test

class RouteValidationTest : ResourceLoader() {

    val ref = ApplicationDeploymentRef(
        environment = "utv",
        application = "reference"
    )

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

        val error = catch { AuroraDeploymentSpecService.validateRoutes(routes, ref) }

        assert(error).isNotNull()
        assert(error?.message).isEqualTo("Application reference in environment utv have routes with duplicate names. Route name=ref1 is duplicated, Route name=ref2 is duplicated.")
    }

    @Test
    fun `Should not allow two routes with the same host`() {

        val routes = AuroraRoute(
            route = listOf(
                Route(objectName = "ref1", host = "test-aurora"),
                Route(objectName = "ref2", host = "test-aurora")
            )
        )

        val error = catch { AuroraDeploymentSpecService.validateRoutes(routes, ref) }

        assert(error).isNotNull()
        assert(error?.message).isEqualTo("Application reference in environment utv have duplicated targets. target=test-aurora is duplicated in routes ref1,ref2.")
    }

    @Test
    fun `Should not allow two routes with the same host and path`() {

        val routes = AuroraRoute(
            route = listOf(
                Route(objectName = "ref1", host = "test-aurora", path = "/aurora"),
                Route(objectName = "ref2", host = "test-aurora", path = "/aurora")
            )
        )

        val error = catch { AuroraDeploymentSpecService.validateRoutes(routes, ref) }

        assert(error).isNotNull()
        assert(error?.message).isEqualTo("Application reference in environment utv have duplicated targets. target=test-aurora/aurora is duplicated in routes ref1,ref2.")
    }
}