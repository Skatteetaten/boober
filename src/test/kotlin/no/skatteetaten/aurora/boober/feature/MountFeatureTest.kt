package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newPersistentVolumeClaim
import com.fkorotkov.kubernetes.newSecret
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.openshift.api.model.DeploymentConfig
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultResults
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class MountFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() =
            MountFeature(vaultProvider, cluster, openShiftClient)

    val vaultProvider: VaultProvider = mockk()

    val configMapJson = """{
              "mounts": {
                "mount": {
                  "type": "ConfigMap",
                  "path": "/u01/foo",
                  "mountName" : "mount",
                  "content" : {
                    "FOO" : "BAR"
                   }
                }
              }  
             }""".trimIndent()

    val secretVaultJson = """{
              "mounts": {
                "mount": {
                  "type": "Secret",
                  "path": "/u01/foo",
                  "secretVault" : "foo"
                }
              }  
             }""".trimIndent()

    val existingSecretJson = """{
              "mounts": {
                "mount": {
                  "type": "Secret",
                  "path": "/u01/foo",
                  "exist" : true
                }
              }  
             }""".trimIndent()

    val existingPVCJson = """{
              "mounts": {
                "mount": {
                  "type": "PVC",
                  "path": "/u01/foo",
                  "exist" : true
                }
              }  
             }""".trimIndent()

    @Test
    fun `Should generate handlers`() {

        val handlers = createAuroraConfigFieldHandlers(configMapJson)

        val mountHandlers = handlers.filter { it.name.startsWith("mounts") }
        assertThat(mountHandlers.size).isEqualTo(7)
    }

    @Test
    fun `Should generate handlers for no mounts`() {
        val handlers = createAuroraConfigFieldHandlers("{}")

        val mountHandlers = handlers.filter { it.name.startsWith("mounts") }
        assertThat(mountHandlers.size).isEqualTo(0)
    }

    enum class MountValidationTestCases(
        val jsonFragment: String,
        val errorMessage: String
    ) {

        PATH_REQUIRED(""" "type" : "ConfigMap" """, "Path is required for mount"),
        WRONG_MOUNT_TYPE(""" "type" : "Foo" """, "Must be one of [ConfigMap, Secret, PVC]"),
        CONFIGMAP_MOUNT_REQUIRES_CONTENT(
            """"type": "ConfigMap", "path": "/u01/foo"""",
            "Mount with type=ConfigMap namespace=paas-utv name=mount does not have required content block"
        )
    }

    @ParameterizedTest
    @EnumSource(MountValidationTestCases::class)
    fun `Mount validation test`(data: MountValidationTestCases) {
        assertThat {
            createAuroraDeploymentContext(
                """{
              "mounts": {
                "mount": {
                ${data.jsonFragment}
                }
              }  
             }""".trimIndent(),
                fullValidation = false
            )
        }.singleApplicationError(data.errorMessage)
    }

    @Test
    fun `test deep validation mount exist in cluster`() {

        every { openShiftClient.resourceExists("secret", "paas-utv", "mount") } returns false

        assertThat { createAuroraDeploymentContext(existingSecretJson) }
            .singleApplicationError("Required existing resource with type=Secret namespace=paas-utv name=mount does not exist")
    }

    @Test
    fun `test deep validation auroraVault exist`() {

        every { vaultProvider.vaultExists("paas", "foo") } returns false

        assertThat { createAuroraDeploymentContext(secretVaultJson) }
            .singleApplicationError("Referenced Vault foo in Vault Collection paas does not exist")
    }

    @Test
    fun `should generate configMap`() {

        val resources = genereateResources(configMapJson)
        assertThat(resources.size).isEqualTo(1)

        val configMap = resources.first().resource as ConfigMap
        assertThat(configMap.metadata.name).isEqualTo("simple-mount")
        assertThat(configMap.metadata.namespace).isEqualTo("paas-utv")
        assertThat(configMap.data).isEqualTo(mapOf("FOO" to "BAR"))
    }

    @Test
    fun `should modify deploymentConfig and add configMap`() {

        val resource = genereateResources(
            app = configMapJson,
            existingResources = mutableSetOf(createdcAuroraResource())
        )

        assertThat(resource.size).isEqualTo(2)
        val auroraResource = resource.first()

        assertThat(auroraResource.sources.first().feature).isEqualTo(MountFeature::class.java)
        val dc = auroraResource.resource as DeploymentConfig
        assertDeploymentConfigMountsVolume(dc, resource.last().resource)
    }

    @Test
    fun `should modify deploymentConfig and add auroraVaultSecret`() {

        every { vaultProvider.vaultExists("paas", "foo") } returns true

        every { vaultProvider.findVaultData(listOf(VaultRequest("paas", "foo"))) } returns
            VaultResults(mapOf("foo" to mapOf("latest.properties" to "FOO=bar\nBAR=baz\n".toByteArray())))

        val resource = genereateResources(secretVaultJson, existingResources = mutableSetOf(createdcAuroraResource()))

        assertThat(resource.size).isEqualTo(2)
        val auroraResource = resource.first()

        assertThat(auroraResource.sources.first().feature).isEqualTo(MountFeature::class.java)
        val dc = auroraResource.resource as DeploymentConfig
        assertDeploymentConfigMountsVolume(dc, resource.last().resource)
    }

    @Test
    fun `should modify deploymentConfig and add existing secret`() {

        every { openShiftClient.resourceExists("secret", "paas-utv", "mount") } returns true

        val resource =
            genereateResources(existingSecretJson, existingResources = mutableSetOf(createdcAuroraResource()))

        assertThat(resource.size).isEqualTo(1)
        val auroraResource = resource.first()
        val secret = newSecret {
            metadata {
                name = "mount"
                namespace = "paas-utv"
            }
        }

        assertThat(auroraResource.sources.first().feature).isEqualTo(MountFeature::class.java)
        val dc = auroraResource.resource as DeploymentConfig
        assertDeploymentConfigMountsVolume(dc, secret)
    }

    @Test
    fun `should modify deploymentConfig and add existing pvc`() {

        every { openShiftClient.resourceExists("persistentvolumeclaim", "paas-utv", "mount") } returns true

        val resource = genereateResources(existingPVCJson, existingResources = mutableSetOf(createdcAuroraResource()))

        assertThat(resource.size).isEqualTo(1)
        val auroraResource = resource.first()
        val secret = newPersistentVolumeClaim {
            metadata {
                name = "mount"
                namespace = "paas-utv"
            }
        }

        assertThat(auroraResource.sources.first().feature).isEqualTo(MountFeature::class.java)
        val dc = auroraResource.resource as DeploymentConfig
        assertDeploymentConfigMountsVolume(dc, secret)
    }
}