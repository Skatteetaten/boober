package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.openshift.api.model.DeploymentConfig
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultProvider
import no.skatteetaten.aurora.boober.service.vault.VaultService
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class MountFeatureHandlerTest : AbstractFeatureTest() {
    override val feature: Feature
        get() =
            MountFeature(VaultProvider(vaultService), cluster, openShiftClient)

    val vaultService: VaultService = mockk()

    val configMapJson = """{
              "mounts": {
                "mount": {
                  "type": "ConfigMap",
                  "path": "/u01/foo",
                  "content" : {
                    "FOO" : "BAR"
                   }
                }
              }  
             }""".trimIndent()

    @Test
    fun `Should generate handlers`() {
        val handlers = createAuroraConfigFieldHandlers(configMapJson)

        val mountHandlers = handlers.filter { it.name.startsWith("mounts") }
        assertThat(mountHandlers.size).isEqualTo(7)
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

        val json = """{
              "mounts": {
                "mount": {
                  "type": "Secret",
                  "path": "/u01/foo",
                  "exist" : true
                }
              }  
             }""".trimIndent()
        assertThat { createAuroraDeploymentContext(json) }
            .singleApplicationError("Required existing resource with type=Secret namespace=paas-utv name=mount does not exist")
    }

    @Test
    fun `test deep validation auroraVault exist`() {

        every { vaultService.vaultExists("paas", "foo") } returns false

        val json = """{
              "mounts": {
                "mount": {
                  "type": "Secret",
                  "path": "/u01/foo",
                  "secretVault" : "foo"
                }
              }  
             }""".trimIndent()
        assertThat { createAuroraDeploymentContext(json) }
            .singleApplicationError("Referenced Vault foo in Vault Collection paas does not exist")
    }

    @Test
    fun `should generate configMap`() {

        val resources = generateResources(configMapJson)
        assertThat(resources.size).isEqualTo(1)

        val configMap = resources.first().resource as ConfigMap
        assertThat(configMap.metadata.name).isEqualTo("simple-mount")
        assertThat(configMap.metadata.namespace).isEqualTo("paas-utv")
        assertThat(configMap.data).isEqualTo(mapOf("FOO" to "BAR"))
    }

    @Test
    fun `should modify deploymentConfig and add mount`() {

        val resource = mutableSetOf(dcAuroraResource)
        modifyResources(configMapJson, existingResources = resource)

        assertThat(resource.size).isEqualTo(2)
        val auroraResource = resource.first()
        val configMap = resource.last().resource as ConfigMap

        assertThat(auroraResource.sources.first().feature).isEqualTo(MountFeature::class.java)

        val dc = auroraResource.resource as DeploymentConfig
        val podSpec = dc.spec.template.spec
        assertThat(podSpec.volumes.size).isEqualTo(2)
        assertThat(podSpec.volumes[1].name).isEqualTo(podSpec.containers[0].volumeMounts[1].name)
        assertThat(podSpec.volumes[1].configMap.name).isEqualTo(configMap.metadata.name)
        assertThat(podSpec.containers[0].volumeMounts.size).isEqualTo(2)
        assertThat(podSpec.containers[0].volumeMounts[1].mountPath).isEqualTo(podSpec.containers[0].env.last().value)
        assertThat(podSpec.containers[0].env.size).isEqualTo(3)
        assertThat(podSpec.containers[0].env.last().name).isEqualTo("VOLUME_MOUNT")
    }
}