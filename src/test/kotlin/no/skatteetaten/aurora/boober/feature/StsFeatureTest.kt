package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.openshift.api.model.DeploymentConfig
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.time.Duration
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.Paths.secretsPath
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioningResult
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test

class StsFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = StsFeature(stsProvisioner)

    val stsProvisioner: StsProvisioner = mockk()

    @Test
    fun `should not validate if template type without groupId and commonName`() {

        assertThat {
            createAuroraDeploymentContext(
                """{
                "type" : "template",
                "certificate" : true
            }"""
            )
        }.singleApplicationError("groupId is required for type=template/localtemplate if certificate/commonName is not set")
    }

    @Test
    fun `should provision sts certificate`() {

        val cn = "org.test.simple"
        val cert = StsProvisioner.createStsCert(ByteArrayInputStream(loadByteResource("keystore.jks")), "ca", "")
        val provisioningResult = StsProvisioningResult(cn, cert, cert.notAfter - Duration.ofDays(14))
        every { stsProvisioner.generateCertificate("org.test.simple", "simple", "utv") } returns
            provisioningResult

        val resources = generateResources(
            """{
            "certificate" : true,
            "groupId" : "org.test"
           }""", createEmptyDeploymentConfig()
        )

        assertThat(resources.size).isEqualTo(2)

        val (dcResource, secretResource) = resources.toList()

        assertThat(secretResource)
            .auroraResourceCreatedByThisFeature()

        val secret = secretResource.resource as Secret
        assertThat(secret.data.keys).isEqualTo(
            setOf(
                "privatekey.key",
                "keystore.jks",
                "certificate.crt",
                "descriptor.properties"
            )
        )
        assertThat(secret.metadata.annotations).isEqualTo(
            mapOf(
                "gillis.skatteetaten.no/app" to "simple",
                "gillis.skatteetaten.no/commonName" to "org.test.simple"
            )
        )
        assertThat(secret.metadata.labels.keys).isEqualTo(setOf("stsRenewAfter"))
        assertThat(dcResource)
            .auroraResourceModifiedByThisFeatureWithComment("Added env vars, volume mount, volume")

        val dc = dcResource.resource as DeploymentConfig
        val env = dc.spec.template.spec.containers.first().env.associate {
            it.name to it.value
        }
        val baseUrl = "$secretsPath/simple-cert"
        assertThat(env).isEqualTo(
            mapOf(
                "STS_CERTIFICATE_URL" to "$baseUrl/certificate.crt",
                "STS_PRIVATE_KEY_URL" to "$baseUrl/privatekey.key",
                "STS_KEYSTORE_DESCRIPTOR" to "$baseUrl/descriptor.properties",
                "VOLUME_SIMPLE_CERT" to baseUrl
            )
        )
    }

    @Test
    fun `Should use overridden cert name when set to default at higher level`() {

        val ctx = createAuroraDeploymentContext("""{
            "groupId" : "org.simple",
            "certificate" : {
              "commonName" : "fooo"
            }
        }""", files = listOf(AuroraConfigFile("utv/about.json", """{ "certificate" : "true" }""")))

        assertThat(ctx.spec.certificateCommonName).isEqualTo("fooo")
    }

    @Test
    fun `Should use overridden cert name when explicitly disabled at higher level`() {

        val ctx = createAuroraDeploymentContext("""{
            "groupId" : "org.simple",
            "certificate" : {
              "commonName" : "fooo"
            }
        }""", files = listOf(AuroraConfigFile("utv/about.json", """{ "certificate" : false }""")))

        assertThat(ctx.spec.certificateCommonName).isEqualTo("fooo")
    }
}
