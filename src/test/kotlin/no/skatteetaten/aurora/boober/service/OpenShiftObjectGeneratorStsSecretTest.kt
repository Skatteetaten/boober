package no.skatteetaten.aurora.boober.service

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import io.fabric8.kubernetes.api.model.OwnerReference
import no.skatteetaten.aurora.boober.service.internal.StsSecretGenerator
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioner.Companion.createStsCert
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioningResult
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.Duration

class OpenShiftObjectGeneratorStsSecretTest : AbstractOpenShiftObjectGeneratorTest() {

    @Test
    fun `sts secret is correctly generated`() {

        val cn = "foo.bar"

        val cert = createStsCert(ByteArrayInputStream(loadByteResource("keystore.jks")), "ca", "")
        val provisioningResult = StsProvisioningResult(cn, cert, cert.notAfter - Duration.ofDays(14))

        val secret = StsSecretGenerator.create(
            appName = "aos-simple",
            stsProvisionResults = provisioningResult,
            labels = mapOf(),
            ownerReference = OwnerReference(),
            namespace = "aos-test"
        )

        assertThat(secret).isNotNull()

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
                "gillis.skatteetaten.no/app" to "aos-simple",
                "gillis.skatteetaten.no/commonName" to "foo.bar"
            )
        )
        assertThat(secret.metadata.labels.keys).isEqualTo(setOf("stsRenewAfter"))
    }
}
