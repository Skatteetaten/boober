package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.model.ApplicationId.aid
import static no.skatteetaten.aurora.boober.service.ExternalResourceProvisioner.createSchemaProvisionRequestsFromDeploymentSpec
import static no.skatteetaten.aurora.boober.service.OpenShiftObjectGeneratorUtils.createObjectGenerator

import com.fasterxml.jackson.databind.JsonNode

import groovy.json.JsonOutput
import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.AbstractAuroraDeploymentSpecTest
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient

class OpenShiftObjectGeneratorDeploymentConfigTest extends AbstractAuroraDeploymentSpecTest {

  OpenShiftObjectGenerator objectGenerator = createObjectGenerator()

  def "Verify properties entries contains a line for each property"() {

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
      def dc = objectGenerator.generateDeploymentConfig(deploymentSpec, "deploy-id")

    then:
      println JsonOutput.prettyPrint(dc.toString())
  }
}
