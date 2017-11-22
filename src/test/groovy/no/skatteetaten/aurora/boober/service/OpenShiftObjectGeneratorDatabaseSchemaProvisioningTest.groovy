package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.model.ApplicationId.aid
import static no.skatteetaten.aurora.boober.service.ExternalResourceProvisioner.createSchemaProvisionRequestsFromDeploymentSpec

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec

class OpenShiftObjectGeneratorDatabaseSchemaProvisioningTest extends AbstractOpenShiftObjectGeneratorTest {

  OpenShiftObjectGenerator objectGenerator = createObjectGenerator()

  def "A"() {

    given:

      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec([
          "about.json"        : DEFAULT_ABOUT,
          "utv/about.json"    : DEFAULT_UTV_ABOUT,
          "reference.json"    : REF_APP_JSON,
          "utv/reference.json": '''{}'''
      ], aid("utv", "reference"))

      def provisioningResult = new ProvisioningResult(
          new SchemaProvisionResults([new SchemaProvisionResult(
              createSchemaProvisionRequestsFromDeploymentSpec(deploymentSpec)[0],
              new DbhSchema(""))
          ]))

    when:
      def objects = objectGenerator.generateApplicationObjects('deploy-id', deploymentSpec, provisioningResult)
      def deploymentConfig = objects.find { it.get("kind").textValue().toLowerCase() == "deploymentconfig" }

    then:
      deploymentConfig != null
      objects.each {
        println it
      }
  }
}
