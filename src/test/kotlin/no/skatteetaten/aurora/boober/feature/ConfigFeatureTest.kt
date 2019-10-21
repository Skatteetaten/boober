package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fkorotkov.kubernetes.newEnvVar
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test

class ConfigFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = ConfigFeature()

    @Test
    fun `modify dc and add config`() {

        val resources = generateResources(
            """{ 
               "config": {
                 "FOO": "BAR"
                }
           }""", createEmptyDeploymentConfig()
        )

        assertThat(resources.size).isEqualTo(1)
        val dcResource = resources.first()

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added env vars, volume mount, volume")
        val dc = dcResource.resource as DeploymentConfig

        val env = dc.spec.template.spec.containers.first().env.first()

        assertThat(env).isEqualTo(newEnvVar {
            name = "FOO"
            value = "BAR"
        })
    }

    @Test
    fun `create configmap for nested configuration and mount it`() {

        val resources = generateResources(
            """{ 
               "config": {
                 "latest": {
                   "FOO": "BAR"
                 }
                }
           }""", createEmptyDeploymentConfig()
        )

        assertThat(resources.size).isEqualTo(2)
        val dcResource = resources.first()
        val configMapResource = resources.last()
        assertThat(configMapResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("configMap.json")
        assertThat(dcResource).auroraResourceMountsAttachment(configMapResource.resource)
    }
}