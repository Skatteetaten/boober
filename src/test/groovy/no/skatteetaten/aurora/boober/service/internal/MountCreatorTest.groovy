package no.skatteetaten.aurora.boober.service.internal

import no.skatteetaten.aurora.boober.model.AbstractAuroraDeploymentSpecTest
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ProvisioningResult
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultResults

class MountCreatorTest extends AbstractAuroraDeploymentSpecTest {

  def provisioningResult = new ProvisioningResult(null, new VaultResults([foo: [FOO: "BAR"]]))

  def "Creates vault for secretVault"() {

    given:
      def auroraConfigJson = [
          "about.json"         : DEFAULT_ABOUT,
          "utv/about.json"     : DEFAULT_UTV_ABOUT,
          "aos-simple.json"    : AOS_SIMPLE_JSON,
          "utv/aos-simple.json": '''{ "certificate": false, "secretVault": "foo" }'''
      ]

      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    when:
      def mounts = MountCreatorKt.findAndCreateMounts(deploymentSpec, provisioningResult)

    then:
      mounts.size() == 1
      mounts[0].content == [FOO: "BAR"]
      mounts[0].secretVaultName == "foo"
  }

  def "Creates vault for secret mount"() {

    given:
      def auroraConfigJson = modify(defaultAuroraConfig(), "utv/aos-simple.json", {
        certificate = false
        mounts = [
            ("secret-mount"): [
                type       : "Secret",
                path       : "/u01/foo",
                secretVault: "foo"
            ]
        ]
      })

      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    when:
      def mounts = MountCreatorKt.findAndCreateMounts(deploymentSpec, provisioningResult)

    then:
      mounts.size() == 1
      mounts[0].content == [FOO: "BAR"]
      mounts[0].secretVaultName == "foo"
  }
}
