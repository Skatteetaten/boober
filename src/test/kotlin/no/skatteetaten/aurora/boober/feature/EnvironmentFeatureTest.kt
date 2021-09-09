package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.security.core.authority.SimpleGrantedAuthority
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftGroups
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.applicationErrors
import no.skatteetaten.aurora.boober.utils.singleApplicationError

class EnvironmentFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = EnvironmentFeature(openShiftClient, userDetailsProvider)

    val userDetailsProvider: UserDetailsProvider = mockk()

    @Test
    fun `should fail validation if current user is not in admin group`() {

        every { openShiftClient.getGroups() } returns OpenShiftGroups(
            mapOf(
                "APP_PaaS_utv" to listOf("luke"),
                "APP_PaaS_drift" to listOf("luke")
            )
        )

        every { userDetailsProvider.getAuthenticatedUser() } returns User("hero", "token", "Jayne Cobb")

        assertThat {
            createAuroraDeploymentContext()
        }.singleApplicationError("User=Jayne Cobb does not have access to admin this environment from the groups=[APP_PaaS_drift, APP_PaaS_utv]")
    }

    @Test
    fun `should fail validation if specified admin groups are empty`() {

        every { openShiftClient.getGroups() } returns OpenShiftGroups(
            mapOf(
                "APP_PaaS_utv" to emptyList(),
                "APP_PaaS_drift" to emptyList()
            )
        )

        every { userDetailsProvider.getAuthenticatedUser() } returns User("hero", "token", "Jayne Cobb")

        assertThat {
            createAuroraDeploymentContext()
        }.applicationErrors(
            "All groups=[APP_PaaS_drift, APP_PaaS_utv] are empty",
            "User=Jayne Cobb does not have access to admin this environment from the groups=[APP_PaaS_drift, APP_PaaS_utv]"
        )
    }

    @Test
    fun `should fail validation if admin group is empty`() {

        every { openShiftClient.getGroups() } returns OpenShiftGroups(
            mapOf(
                "APP_PaaS_utv" to listOf("luke")
            )
        )

        every { userDetailsProvider.getAuthenticatedUser() } returns User("hero", "token", "Jayne Cobb")

        assertThat {
            createAuroraDeploymentContext(
                files = listOf(
                    AuroraConfigFile(
                        "utv/about.json", contents = """{
                  "permissions": {
                    "admin" : ""
                   }
                }"""
                    )
                )
            )
        }.singleApplicationError("permissions.admin cannot be empty")
    }

    @Test
    fun `should fail validation if admin group does not exist`() {

        every { openShiftClient.getGroups() } returns OpenShiftGroups(
            mapOf(
                "APP_PaaS_test" to listOf("luke")
            )
        )

        every { userDetailsProvider.getAuthenticatedUser() } returns User("hero", "token", "Jayne Cobb")

        assertThat {
            createAuroraDeploymentContext(fullValidation = false)
        }.singleApplicationError("[APP_PaaS_drift, APP_PaaS_utv] are not valid groupNames")
    }

    @Test
    fun `Should fail when name is not valid DNS952 label`() {
        assertThat {
            createAuroraDeploymentContext(
                """{
             "name": "test%qwe)"
            }"""
            )
        }.singleApplicationError("Name must be alphanumeric and no more than 40 characters")
    }

    @Test
    fun `Fails when affiliation is not in about file`() {

        assertThat {
            createAuroraDeploymentContext(
                """{ "affiliation" : "foo"}""",
                files = listOf(
                    AuroraConfigFile(
                        "about.json", """{
                         "schemaVersion": "v1",
                         "permissions": {
                           "admin": "APP_PaaS_utv"
                         },
                         "type": "deploy",
                         "cluster": "utv"
                    }"""
                    )
                )
            )
        }.singleApplicationError("Config for application simple in environment utv contains errors. Invalid Source field=affiliation. Actual source=utv/simple.json (File type: APP). Must be placed within files of type: [GLOBAL, ENV, INCLUDE_ENV].")
    }

    @Test
    fun `Fails when affiliation is too long`() {

        assertThat {
            createAuroraDeploymentContext(
                files = listOf(
                    AuroraConfigFile(
                        "about.json", """{
                         "schemaVersion": "v1",
                         "affiliation" : "this-is-too-damn-long",
                         "permissions": {
                           "admin": "APP_PaaS_utv"
                         },
                         "type": "deploy",
                         "cluster": "utv"
                    }"""
                    )
                )
            )
        }.singleApplicationError("Affiliation can only contain letters and must be no longer than 10 characters")
    }

    @Test
    fun `Fails when application name is too long and artifactId and name is blank`() {

        assertThat {
            createCustomAuroraDeploymentContext(
                ApplicationDeploymentRef("utv", "this-name-is-stupid-stupid-stupidly-long-for-no-reason"),
                "about.json" to FEATURE_ABOUT,
                "this-name-is-stupid-stupid-stupidly-long-for-no-reason.json" to """{ "groupId" : "org.test" }""",
                "utv/about.json" to "{}",
                "utv/this-name-is-stupid-stupid-stupidly-long-for-no-reason.json" to
                    """{ "version" : "1" }"""
            )
        }.singleApplicationError("Name must be alphanumeric and no more than 40 characters")
    }

    enum class PermissionsTestData(val values: String) {
        SINGLE_VALUE(""""APP_PaaS_utv APP_PaaS_drift""""),
        LIST("""["APP_PaaS_utv", "APP_PaaS_drift"] """)
    }

    @ParameterizedTest
    @EnumSource(PermissionsTestData::class)
    fun `Permissions supports both space separated string`(permissions: PermissionsTestData) {

        every { openShiftClient.getGroups() } returns OpenShiftGroups(
            mapOf("APP_PaaS_utv" to listOf("hero"), "APP_PaaS_drift" to listOf())
        )

        every { userDetailsProvider.getAuthenticatedUser() } returns User(
            "hero", "token", "Jayne Cobb", grantedAuthorities = listOf(
                SimpleGrantedAuthority("APP_PaaS_utv")
            )
        )

        val (valid, _) = createAuroraDeploymentContext(
            files = listOf(
                AuroraConfigFile(
                    "about.json", """{
               "schemaVersion": "v1",
               "affiliation" : "paas",
               "permissions": {
                 "admin": ${permissions.values}
               },
               "type": "deploy",
               "cluster": "utv"
           }"""
                )
            )
        )

        assertThat(valid.first().spec.getDelimitedStringOrArrayAsSet("permissions/admin", " "))
            .isEqualTo(
                setOf(
                    "APP_PaaS_utv",
                    "APP_PaaS_drift"
                )
            )
    }
}
