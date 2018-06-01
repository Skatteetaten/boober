package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioner.createStsCert
import static no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioner.findRenewInstant

import no.skatteetaten.aurora.boober.model.AuroraCertificateSpec
import no.skatteetaten.aurora.boober.service.internal.StsSecretGenerator
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioningResult

class OpenShiftObjectGeneratorStsSecretTest extends AbstractOpenShiftObjectGeneratorTest {

  def "sts secret is correctly generated"() {

    given: "generated provisioning command"

      def command = new AuroraCertificateSpec("foo.bar", "1d", "12h")

      def cert = createStsCert(new ByteArrayInputStream(loadByteResource("keystore.jks")), "ca", "")
      def provisioningResult = new StsProvisioningResult(command, cert, findRenewInstant(command))

    when: "secret has been created"
      def secret = StsSecretGenerator.create("aos-simple", provisioningResult, [:])

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
          "gillis.skatteetaten.no/ttl"        : "1d",
          "gillis.skatteetaten.no/renewBefore": "12h",
          "gillis.skatteetaten.no/commonName" : "foo.bar"
      ]
      secret.metadata.labels.keySet() == ["stsRenewAfter"].toSet()
  }

}
