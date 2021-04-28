package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newPersistentVolumeClaim
import com.fkorotkov.kubernetes.newSecret
import com.fkorotkov.kubernetes.newVolumeProjection
import io.fabric8.kubernetes.api.model.ServiceAccountTokenProjection
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultResults
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import org.junit.jupiter.api.Assertions
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
             }"""

    val secretVaultJson = """{
              "mounts": {
                "mount": {
                  "type": "Secret",
                  "path": "/u01/foo",
                  "secretVault" : "foo"
                }
              }  
             }"""

    val disabledMounts = """{
              "mounts": {
                "mount": {
                  "enabled" : false,
                  "type": "Secret",
                  "path": "/u01/foo",
                  "exist" : true
                }
              }  
             }"""

    val existingSecretJson = """{
              "mounts": {
                "mount": {
                  "type": "Secret",
                  "path": "/u01/foo",
                  "exist" : true
                }
              }  
             }"""

    val existingPVCJson = """{
              "mounts": {
                "mount": {
                  "type": "PVC",
                  "path": "/u01/foo",
                  "exist" : true
                }
              }  
             }"""

    val existingPSATJson = """{
              "mounts": {
                "mount": {
                  "type": "PSAT",
                  "path": "/u01/foo",
                  "audience": "dummy-audience",
                  "expirationSeconds": 600
                }
              }  
             }"""

    @Test
    fun `Should generate handlers for no mounts`() {
        val handlers = createAuroraConfigFieldHandlers("{}")

        val mountHandlers = handlers.fields.filter { it.key.startsWith("mounts") }
        assertThat(mountHandlers.size).isEqualTo(0)
    }

    enum class MountValidationTestCases(
        val jsonFragment: String,
        val errorMessage: String
    ) {

        /*  PVC_MUST_EXIST(
              """ "type" : "PVC", "path": "/u01/foo" """,
              "PVC mount=mount must have exist set. We do not support generating mounts for now"
          ),
         */
        SECRET_WITH_VAULT_EXIST(
            """ "path": "/u01/foo", "type" : "Secret", "secretVault" : "foo", "exist" : true """,
            "Secret mount=mount with vaultName set cannot be marked as existing"
        ),
        PATH_REQUIRED(""" "type" : "ConfigMap" """, "Path is required for mount"),
        WRONG_MOUNT_TYPE(""" "type" : "Foo" """, "Must be one of [Secret, PVC, PSAT]")
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
    fun `should modify deploymentConfig and add auroraVaultSecret`() {

        every { vaultProvider.vaultExists("paas", "foo") } returns true

        every { vaultProvider.findVaultData(listOf(VaultRequest("paas", "foo"))) } returns
            VaultResults(mapOf("foo" to mapOf("latest.properties" to "FOO=bar\nBAR=baz\n".toByteArray())))

        val (dcResource, attachmentResource) = generateResources(secretVaultJson, createEmptyDeploymentConfig())

        assertThat(attachmentResource).auroraResourceCreatedByThisFeature()
        assertThat(dcResource).auroraResourceMountsAttachment(attachmentResource.resource)
    }

    @Test
    fun `should modify deploymentConfig and ignore disabled mount`() {

        val resource =
            modifyResources(disabledMounts, createEmptyDeploymentConfig())

        assertThat(resource.size).isEqualTo(1)
    }

    @Test
    fun `should modify deploymentConfig and add existing secret`() {

        every { openShiftClient.resourceExists("secret", "paas-utv", "mount") } returns true

        val resource =
            modifyResources(existingSecretJson, createEmptyDeploymentConfig())

        val dcResource = resource.first()
        val secret = newSecret {
            metadata {
                name = "mount"
                namespace = "paas-utv"
            }
        }

        assertThat(dcResource).auroraResourceMountsAttachment(secret)
    }

    @Test
    fun `should modify deploymentConfig and add existing pvc`() {

        every { openShiftClient.resourceExists("persistentvolumeclaim", "paas-utv", "mount") } returns true

        val resource = modifyResources(existingPVCJson, createEmptyDeploymentConfig())

        val auroraResource = resource.first()
        val secret = newPersistentVolumeClaim {
            metadata {
                name = "mount"
                namespace = "paas-utv"
            }
        }
        assertThat(auroraResource).auroraResourceMountsAttachment(secret)
    }

    @Test
    fun `should modify deploymentConfig and add psat`() {
        every { openShiftClient.k8sVersionOfAtLeast("1.16") } returns true

        val resource = modifyResources(existingPSATJson, createEmptyDeploymentConfig())

        val auroraResource = resource.first()
        val satp = ServiceAccountTokenProjection("dummy-audience", 600L, "psat")
        val psat = newVolumeProjection {
                serviceAccountToken = satp
        }

        assertThat(auroraResource).auroraResourceMountsPsat(psat)
        assertThat(auroraResource).auroraResourceMatchesFile("dc-with-psat.json")
    }

    @Test
    fun `psat is not supported in old k8s`() {
        every { openShiftClient.version() } returns "1.11.4"

        val exception = Assertions.assertThrows(MultiApplicationValidationException::class.java) {
            modifyResources(existingPSATJson, createEmptyDeploymentConfig())
        }
        Assertions.assertEquals(
            1,
            exception.errors.size,
            "Expecting exactly one exception, but got: " + exception.errors
        )
    }

    /**
     * The reason for the minimal value of 10 minutes can be gleaned from the
     * documentation:
     * <a href="https://access.redhat.com/documentation/en-us/openshift_container_platform/4.6/html/authentication_and_authorization/bound-service-account-tokens">https://access.redhat.com/documentation/en-us/openshift_container_platform/4.6/html/authentication_and_authorization/bound-service-account-tokens</a>
     */
    @Test
    fun `should not allow expirationSeconds less than 10 minutes`() {
        val exception = Assertions.assertThrows(MultiApplicationValidationException::class.java) {
            modifyResources(
                """{
              "mounts": {
                "mount": {
                  "type": "PSAT",
                  "path": "/u01/foo",
                  "audience": "dummy-audience",
                  "expirationSeconds": 599,
                }
              }  
             }""", createEmptyDeploymentConfig()
            )
        }
        Assertions.assertEquals(
            1,
            exception.errors.size,
            "Expecting exactly one exception, but got: " + exception.errors
        )
    }

    @Test
    fun `should allow comma separated audience list`() {
        every { openShiftClient.k8sVersionOfAtLeast("1.16") } returns true

        val resource = modifyResources(
            """{
              "mounts": {
                "mount": {
                  "type": "PSAT",
                  "path": "/u01/foo",
                  "audience": "first,second,third",
                  "expirationSeconds": 600
                }
              }  
             }""", createEmptyDeploymentConfig()
        )
        // ,second,third
        val auroraResource = resource.first()

        assertThat(auroraResource).auroraResourceMatchesFile("dc-with-several-psats.json")
    }
}
