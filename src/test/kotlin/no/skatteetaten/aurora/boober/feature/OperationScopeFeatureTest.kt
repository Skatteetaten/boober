package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test

class OperationScopeFeatureTest : AbstractFeatureTest() {

    override val feature: Feature
        get() = OperationScopeFeature("my-config-value")

    val userDetailsProvider: UserDetailsProvider = mockk()

    @Test
    fun `check operation scope label`() {

        every { userDetailsProvider.getAuthenticatedUser() } returns User("hero", "token")

        val resources = modifyResources("{}", createEmptyImageStream(), createEmptyDeploymentConfig())
        val label = mapOf("operationScope" to "my-config-value")

        resources.forEach {
            assertThat(it).auroraResourceModifiedByThisFeatureWithComment("Added operationScope")
            assertThat(it.resource.metadata.labels).isEqualTo(label)
        }

        val dcResource = resources.last()
        assertThat(dcResource.sources.last().comment).isEqualTo("Added operationScope")
    }
}
