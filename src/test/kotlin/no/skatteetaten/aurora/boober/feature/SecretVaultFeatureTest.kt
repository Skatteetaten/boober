package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.openshift.api.model.DeploymentConfig
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultRequest
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test

class SecretVaultFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = SecretVaultFeature(vaultProvider, "utv")

    val vaultProvider: VaultProvider = mockk()

    @Test
    fun `should modify deploymentConfig and add auroraVaultSecret`() {

        every { vaultProvider.vaultExists("paas", "foo") } returns true

        val vaultContents = "FOO=bar\nBAR=baz\n".toByteArray()
        every {
            vaultProvider.findFileInVault(
                vaultCollectionName = "paas",
                vaultName = "foo",
                fileName = "latest.properties"
            )
        } returns vaultContents

        every { vaultProvider.findVaultDataSingle(VaultRequest(collectionName = "paas", name = "foo")) } returns
            mapOf("latest.properties" to vaultContents)

        val resource = generateResources(
            """{
              "secretVault" : "foo" 
             }""", createEmptyDeploymentConfig()
        )
        assertThat(resource.size).isEqualTo(2)

        val attachmentResource = resource.last()
        assertThat(attachmentResource).auroraResourceCreatedByThisFeature()
        val secret = attachmentResource.resource as Secret
        assertThat(secret.data.keys.toList()).isEqualTo(listOf("BAR", "FOO"))

        val dcResource = resource.first()
        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added env vars")

        val dc = dcResource.resource as DeploymentConfig

        val env = dc.spec.template.spec.containers.first().env

        assertThat(env.size).isEqualTo(2)

        val (bar, foo) = env.toList()

        assertThat(bar.name).isEqualTo("BAR")
        assertThat(bar.valueFrom.secretKeyRef.key).isEqualTo("BAR")
        assertThat(bar.valueFrom.secretKeyRef.name).isEqualTo(attachmentResource.resource.metadata.name)

        assertThat(foo.name).isEqualTo("FOO")
        assertThat(foo.valueFrom.secretKeyRef.key).isEqualTo("FOO")
        assertThat(foo.valueFrom.secretKeyRef.name).isEqualTo(attachmentResource.resource.metadata.name)
    }

    @Test
    fun `should modify deploymentConfig and add auroraVaultSecrets`() {

        every { vaultProvider.vaultExists("paas", "fooEnv") } returns true
        every { vaultProvider.vaultExists("paas", "barEnv") } returns true

        val vaultContents1 = "FOO=barEnv\n".toByteArray()
        val vaultContents2 = "BAR=baz\n".toByteArray()
        every {
            vaultProvider.findFileInVault(
                vaultCollectionName = "paas",
                vaultName = "fooEnv",
                fileName = "latest.properties"
            )
        } returns vaultContents1

        every {
            vaultProvider.findFileInVault(
                vaultCollectionName = "paas",
                vaultName = "barEnv",
                fileName = "latest.properties"
            )
        } returns vaultContents2


        every { vaultProvider.findVaultDataSingle(VaultRequest(collectionName = "paas", name = "fooEnv")) } returns
            mapOf("latest.properties" to vaultContents1)


        every { vaultProvider.findVaultDataSingle(VaultRequest(collectionName = "paas", name = "barEnv")) } returns
            mapOf("latest.properties" to vaultContents2)

        val resource = generateResources(
            """{
              "secretVaults" : {
                "fooEnv" : {
                   "enabled" : true
                 }, 
                 "barEnv" : {
                   "enabled" : true
                 }
              }
             }""", createEmptyDeploymentConfig()
        )
        assertThat(resource.size).isEqualTo(3)

        val (dcResource, fooResource, barResource) = resource.toList()
        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added env vars")
        val dc = dcResource.resource as DeploymentConfig
        val env = dc.spec.template.spec.containers.first().env
        assertThat(env.size).isEqualTo(2)
        val (fooEnv, barEnv) = env.toList()

        assertThat(barResource).auroraResourceCreatedByThisFeature()
        val bar = barResource.resource as Secret
        assertThat(bar.data.keys.toList()).isEqualTo(listOf("BAR"))
        assertThat(barEnv.name).isEqualTo("BAR")
        assertThat(barEnv.valueFrom.secretKeyRef.key).isEqualTo("BAR")
        assertThat(barEnv.valueFrom.secretKeyRef.name).isEqualTo(barResource.resource.metadata.name)

        val foo = fooResource.resource as Secret
        assertThat(foo.data.keys.toList()).isEqualTo(listOf("FOO"))
        assertThat(fooResource).auroraResourceCreatedByThisFeature()
        assertThat(fooEnv.name).isEqualTo("FOO")
        assertThat(fooEnv.valueFrom.secretKeyRef.key).isEqualTo("FOO")
        assertThat(fooEnv.valueFrom.secretKeyRef.name).isEqualTo(fooResource.resource.metadata.name)
    }
}