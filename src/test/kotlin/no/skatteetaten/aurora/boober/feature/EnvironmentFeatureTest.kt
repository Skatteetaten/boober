package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftGroups
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test
import org.springframework.security.core.authority.SimpleGrantedAuthority

class EnvironmentFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = EnvironmentFeature(openShiftClient, userDetailsProvider)

    val userDetailsProvider: UserDetailsProvider = mockk()

    @Test
    fun `should fail validation if current user is not in admin group`() {

        every { openShiftClient.getGroups() } returns OpenShiftGroups(mapOf(
                "APP_PaaS_utv" to listOf("luke")))

        every { userDetailsProvider.getAuthenticatedUser() } returns User("hero", "token", "Jayne Cobb")

        assertThat {
            createAuroraDeploymentContext()
        }.singleApplicationError("User=Jayne Cobb does not have access to admin this environment from the groups=[APP_PaaS_utv]")
    }

    @Test
    fun `should fail validation if specified admin groups are empty`() {

        every { openShiftClient.getGroups() } returns OpenShiftGroups(mapOf(
                "APP_PaaS_utv" to emptyList()))

        every { userDetailsProvider.getAuthenticatedUser() } returns User("hero", "token", "Jayne Cobb")

        assertThat {
            createAuroraDeploymentContext()
        }.applicationErrors(
                "All groups=[APP_PaaS_utv] are empty",
                "User=Jayne Cobb does not have access to admin this environment from the groups=[APP_PaaS_utv]"
        )
    }

    @Test
    fun `should fail validation if admin group is empty`() {

        every { openShiftClient.getGroups() } returns OpenShiftGroups(mapOf(
                "APP_PaaS_utv" to listOf("luke")))

        every { userDetailsProvider.getAuthenticatedUser() } returns User("hero", "token", "Jayne Cobb")

        assertThat {
            createAuroraDeploymentContext(files = listOf(AuroraConfigFile("utv/about.json", contents = """{
                  "permissions": {
                    "admin" : ""
                   }
                }""")))
        }.singleApplicationError("permissions.admin cannot be empty")
    }

    @Test
    fun `should fail validation if admin group does not exist`() {

        every { openShiftClient.getGroups() } returns OpenShiftGroups(mapOf(
                "APP_PaaS_test" to listOf("luke")))

        every { userDetailsProvider.getAuthenticatedUser() } returns User("hero", "token", "Jayne Cobb")

        assertThat {
            createAuroraDeploymentContext(fullValidation = false)
        }.singleApplicationError("[APP_PaaS_utv] are not valid groupNames")
    }

    @Test
    fun `should generate environment resources`() {
        every { openShiftClient.getGroups() } returns OpenShiftGroups(mapOf(
                "APP_PaaS_utv" to listOf("hero")))

        every { userDetailsProvider.getAuthenticatedUser() } returns User("hero", "token", "Jayne Cobb", grantedAuthorities = listOf(
                SimpleGrantedAuthority("APP_PaaS_utv"))
        )


        val resources = generateResources()

        assertThat(resources.size).isEqualTo(3)
        val (projectResource, namespaceResource, rolebindingResource) = resources.toList()

        assertThat(projectResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("project.json")
        assertThat(namespaceResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("namespace.json")
        assertThat(rolebindingResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("rolebinding.json")
    }
}
