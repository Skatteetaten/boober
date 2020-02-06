package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isNull
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test

class OperationScopeFeatureNoValueTest : AbstractFeatureTest() {

    override val feature: Feature
        get() = OperationScopeFeature("")

    val userDetailsProvider: UserDetailsProvider = mockk()

    @Test
    fun `check operation scope label not set`() {

        every { userDetailsProvider.getAuthenticatedUser() } returns User("hero", "token")

        val resources = modifyResources("{}", createEmptyImageStream(), createEmptyDeploymentConfig())

        resources.forEach {
            assertThat(it.sources).isEmpty()
            assertThat(it.resource.metadata.labels).isNull()
        }
    }
}
