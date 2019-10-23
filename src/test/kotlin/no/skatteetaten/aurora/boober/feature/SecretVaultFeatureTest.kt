package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.openshift.api.model.DeploymentConfig
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultRequest
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test

class SecretVaultFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = SecretVaultFeature(vaultProvider)

    private val vaultProvider: VaultProvider = mockk()

    @Test
    fun `does not have any normal validation rules`() {

        val context = createAuroraDeploymentContext(
            """{
              "secretVault" : "foo" 
             }""", fullValidation = false
        )
        assertThat(context).isNotNull()
    }

    @Test
    fun `should get error if vault does not exist`() {

        every { vaultProvider.vaultExists("paas", "foo") } returns false

        assertThat {
            createAuroraDeploymentContext(
                """{
              "secretVault" : "foo" 
             }"""
            )
        }.applicationErrors(
            "Referenced Vault foo in Vault Collection paas does not exist",
            "File with name=latest.properties is not present in vault=foo in collection=paas"
        )
    }

    @Test
    fun `should modify deploymentConfig and add auroraVaultSecret`() {

        mockVault("foo")
        val resource = generateResources(
            """{
              "secretVault" : "foo" 
             }""", createEmptyDeploymentConfig()
        )
        assertThat(resource.size).isEqualTo(2)
        val dcResource = resource.first()
        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added env vars")
        val dc = dcResource.resource as DeploymentConfig
        val env = dc.spec.template.spec.containers.first().env
        assertThat(env.size).isEqualTo(1)
        val foo = env.first()

        val attachmentResource = resource.last()
        assertEnvVarMounted(attachmentResource, "FOO", foo)
    }

    @Test
    fun `should modify deploymentConfig and add auroraVaultSecret and ignore key`() {

        every { vaultProvider.findVaultKeys("paas", "foo", "latest.properties") } returns setOf("FOO", "BAR")

        mockVault("foo", contents = "FOO=secretValue\nBAR=value\n")
        val resource = generateResources(
            """{
              "secretVault": {
                "name" : "foo",
                "keys" : ["FOO"] 
               }
             }""", createEmptyDeploymentConfig()
        )
        assertThat(resource.size).isEqualTo(2)
        val dcResource = resource.first()
        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added env vars")
        val dc = dcResource.resource as DeploymentConfig
        val env = dc.spec.template.spec.containers.first().env
        assertThat(env.size).isEqualTo(1)
        val foo = env.first()

        val attachmentResource = resource.last()
        assertEnvVarMounted(attachmentResource, "FOO", foo)
    }

    @Test
    fun `should modify deploymentConfig and add auroraVaultSecret from custom file`() {

        mockVault("foo", "foo.properties")
        val resource = generateResources(
            """{
              "secretVaults" : {
                "foo": {
                  "file" : "foo.properties"
                }
              }
             }""", createEmptyDeploymentConfig()
        )
        assertThat(resource.size).isEqualTo(2)
        val dcResource = resource.first()
        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added env vars")
        val dc = dcResource.resource as DeploymentConfig
        val env = dc.spec.template.spec.containers.first().env
        assertThat(env.size).isEqualTo(1)
        val foo = env.first()

        val attachmentResource = resource.last()
        assertEnvVarMounted(attachmentResource, "FOO", foo)
    }

    @Test
    fun `should skip disabled mount`() {

        val resource = generateResources(
            """{
              "secretVaults" : {
                 "foo": {
                  "enabled" : false
                  }
              }
             }"""
        )
        assertThat(resource.size).isEqualTo(0)
    }

    @Test
    fun `should modify deploymentConfig and add auroraVaultSecrets`() {

        mockVault("foo")
        mockVault("bar")

        val resource = generateResources(
            """{
              "secretVaults" : {
                "foo" : {
                   "enabled" : true
                 }, 
                 "bar" : {
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

        assertEnvVarMounted(barResource, "BAR", barEnv)
        assertEnvVarMounted(fooResource, "FOO", fooEnv)
    }

    @Test
    fun `should modify deploymentConfig and add auroraVaultSecrets from simple and expanded syntax`() {

        mockVault("foo")
        mockVault("bar")

        val resource = generateResources(
            """{
              "secretVault" : "bar",
              "secretVaults" : {
                 "foo" : {
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
        assertEnvVarMounted(fooResource, "FOO", fooEnv)
        assertEnvVarMounted(barResource, "BAR", barEnv)
    }

    private fun mockVault(
        name: String,
        fileName: String = "latest.properties",
        contents: String = "${name.toUpperCase()}=secretValue\n"
    ) {
        val vaultContents1 = contents.toByteArray()
        every { vaultProvider.vaultExists("paas", name) } returns true
        every {
            vaultProvider.findFileInVault(
                vaultCollectionName = "paas",
                vaultName = name,
                fileName = fileName
            )
        } returns vaultContents1

        every { vaultProvider.findVaultDataSingle(VaultRequest(collectionName = "paas", name = name)) } returns
            mapOf(fileName to vaultContents1)
    }

    private fun assertEnvVarMounted(
        resource: AuroraResource,
        envName: String,
        envVar: EnvVar
    ) {
        val foo = resource.resource as Secret
        assertThat(foo.data.keys.toList()).isEqualTo(listOf(envName))
        assertThat(resource).auroraResourceCreatedByThisFeature()
        assertThat(envVar.name).isEqualTo(envName)
        assertThat(envVar.valueFrom.secretKeyRef.key).isEqualTo(envName)
        assertThat(envVar.valueFrom.secretKeyRef.name).isEqualTo(resource.resource.metadata.name)
    }
}