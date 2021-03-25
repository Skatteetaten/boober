package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.Test
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.fabric8.openshift.api.model.DeploymentConfig
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest

class CommonLabelFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = CommonLabelFeature(userDetailsProvider)

    val userDetailsProvider: UserDetailsProvider = mockk()

    @Test
    fun `should label resources`() {

        every { userDetailsProvider.getAuthenticatedUser() } returns User("hero", "token")

        val resources = modifyResources("{}", createEmptyImageStream(), createEmptyDeploymentConfig())

        val labels = mapOf(
            "app" to "simple",
            "updatedBy" to userDetailsProvider.getAuthenticatedUser().username.replace(":", "-"),
            "updatedAt" to "0",
            "lastUpdatedYear" to "1970",
            "affiliation" to "paas",
            "name" to "simple"
        )
        resources.forEach {
            assertThat(it).auroraResourceModifiedByThisFeatureWithComment("Added commonLabels to metadata")
            assertThat(it.resource.metadata.labels).isEqualTo(labels)
        }

        val dcResource = resources.last()

        val dc = dcResource.resource as DeploymentConfig

        assertThat(dcResource.sources.last().comment).isEqualTo("Added commonLabels to podTemplate")

        assertThat(dc.spec.template.metadata.labels).isEqualTo(labels)
    }
}
