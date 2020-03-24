package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test

class OperationScopeFeatureTest {

    class OperationScopeFeatureWithValue : AbstractFeatureTest() {

        override val feature: Feature
            get() = OperationScopeFeature("my-config-value")
    }

    class OperationScopeFeatureWithoutValue : AbstractFeatureTest() {

        override val feature: Feature
            get() = OperationScopeFeature("")
    }

    @Test
    fun `set operationScope label`() {

        val feature = OperationScopeFeatureWithValue()

        val imageStream = feature.createEmptyImageStream()
        val emptyDeploymentConfig = feature.createEmptyDeploymentConfig()

        val resources = feature.modifyResources(
            "{}",
            imageStream,
            emptyDeploymentConfig
        )
        val label = mapOf("operationScope" to "my-config-value")

        resources.forEach {
            assertThat(it.sources.first().comment).isEqualTo("Added operationScope label to metadata")
            assertThat(it.resource.metadata.labels).isEqualTo(label)
        }

        val dcResource = resources.last()
        assertThat(dcResource.sources.last().comment).isEqualTo("Added operationScope label to podTemplate")
    }

    @Test
    fun `operationScope is not set`() {
        val feature = OperationScopeFeatureWithoutValue()
        val emptyImageStream = feature.createEmptyImageStream()
        val emptyDeploymentConfig = feature.createEmptyDeploymentConfig()

        val resources = feature.modifyResources(
            "{}",
            emptyImageStream,
            emptyDeploymentConfig
        )

        resources.forEach {
            assertThat(it.sources).isEmpty()
            assertThat(it.resource.metadata.labels).isNull()
        }
    }
}
