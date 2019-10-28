package no.skatteetaten.aurora.boober.unit

import assertk.all
import assertk.assertThat
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.service.DeployService
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.core.authority.SimpleGrantedAuthority

class DeployServiceProjectTerminatingTest {

    val openShiftClient: OpenShiftClient = mockk()
    val userDetailsProvider: UserDetailsProvider = mockk()

    val deployService = DeployService(
        auroraConfigService = mockk(),
        openShiftCommandBuilder = mockk(),
        openShiftClient = openShiftClient,
        cantusService = mockk(),
        redeployService = mockk(),
        userDetailsProvider = userDetailsProvider,
        deployLogService = mockk(),
        cluster = "utv",
        dockerRegistry = "docker.registry",
        releaseDockerRegistry = "docker.registry.release",
        auroraDeploymentContextService = mockk()
    )

    val group = "APP_demo_drift"

    @BeforeEach
    fun setup() {
        every { userDetailsProvider.getAuthenticatedUser() } returns User(
            username = "aurora",
            token = "token",
            fullName = "Aurora OpenShift",
            grantedAuthorities = listOf(SimpleGrantedAuthority(group))
        )
        every { openShiftClient.projectExists(any()) } throws IllegalStateException("Project is terminating")
    }

    @Test
    fun `Should return with error if project is terminating`() {

        assertThat {
            deployService.prepareDeployEnvironment("demo-foo", emptySet())
        }.isFailure().all {
            isInstanceOf(IllegalStateException::class)
        }
    }
}
