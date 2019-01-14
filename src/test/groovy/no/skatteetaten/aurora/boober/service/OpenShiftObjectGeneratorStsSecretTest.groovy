package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioner.createStsCert

import java.time.Duration

import io.fabric8.kubernetes.api.model.OwnerReference
import no.skatteetaten.aurora.boober.service.internal.StsSecretGenerator
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioningResult

class OpenShiftObjectGeneratorStsSecretTest extends AbstractOpenShiftObjectGeneratorTest {

  def "sts secret is correctly generated"() {

    given: "generated provisioning command"

    def cn="foo.bar"

      def cert = createStsCert(new ByteArrayInputStream(loadByteResource("keystore.jks")), "ca", "")
      def provisioningResult = new StsProvisioningResult(cn, cert, cert.notAfter - Duration.ofDays(14))

    when: "secret has been created"
      def secret = StsSecretGenerator.create("aos-simple", provisioningResult, [:], new OwnerReference())

    then: "the secret should be correct"
      secret != null
      secret.data.keySet() == [
          'privatekey.key',
          'keystore.jks',
          'certificate.crt',
          'descriptor.properties'
      ].toSet()
      secret.metadata.annotations == [
          "gillis.skatteetaten.no/app"        : "aos-simple",
          "gillis.skatteetaten.no/commonName" : "foo.bar"
      ]
      secret.metadata.labels.keySet() == ["stsRenewAfter"].toSet()
  }

}
