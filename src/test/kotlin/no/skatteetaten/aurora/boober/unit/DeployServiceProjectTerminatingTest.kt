package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isInstanceOf
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.Permission
import no.skatteetaten.aurora.boober.model.Permissions
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.DeployService
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.core.authority.SimpleGrantedAuthority

class DeployServiceProjectTerminatingTest {
    val ENV_NAME = "booberdev"
    val APP_NAME = "aos-simple"
    val ref = AuroraConfigRef("aos", "master", "123")

    val aid = ApplicationDeploymentRef(ENV_NAME, APP_NAME)

    val openShiftClient: OpenShiftClient = mockk()
    val userDetailsProvider: UserDetailsProvider = mockk()

    val deployService = DeployService(
        auroraConfigService = mockk(),
        openShiftCommandBuilder = mockk(),
        openShiftClient = openShiftClient,
        dockerService = mockk(),
        resourceProvisioner = mockk(),
        redeployService = mockk(),
        userDetailsProvider = userDetailsProvider,
        deployLogService = mockk(),
        cluster = "utv",
        dockerRegistry = "docker.registry",
        releaseDockerRegistry = "docker.registry"
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

        val env = AuroraDeployEnvironment(
            affiliation = "demo",
            envName = "foo",
            permissions = Permissions(
                admin = Permission(setOf(group))
            ), ttl = null
        )

        assertThat {
            deployService.prepareDeployEnvironment(env)
        }.thrownError {
            isInstanceOf(IllegalStateException::class)
        }
    }
}
