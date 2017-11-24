package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.model.ApplicationId.aid
import static no.skatteetaten.aurora.boober.service.ExternalResourceProvisioner.createSchemaProvisionRequestsFromDeploymentSpec

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec

class OpenShiftObjectGeneratorDatabaseSchemaProvisioningTest extends AbstractOpenShiftObjectGeneratorTest {

  OpenShiftObjectGenerator objectGenerator = createObjectGenerator()

  def "A"() {

    given:

      def appName = "reference"
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec([
          "about.json"        : DEFAULT_ABOUT,
          "utv/about.json"    : DEFAULT_UTV_ABOUT,
          "reference.json"    : REF_APP_JSON,
          "utv/reference.json": '''{}'''
      ], aid("utv", appName))

      def schema = new DbhSchema("", [name: appName])
      def provisioningResult = new ProvisioningResult(
          new SchemaProvisionResults([new SchemaProvisionResult(
              createSchemaProvisionRequestsFromDeploymentSpec(deploymentSpec)[0],
              schema)
          ]))

    when:
      def objects = objectGenerator.generateApplicationObjects('deploy-id', deploymentSpec, provisioningResult)
      def deploymentConfig = objects.find { it.get("kind").textValue().toLowerCase() == "deploymentconfig" }
      def secret = objects.find { it.get("kind").textValue().toLowerCase() == "secret" }
      secret = new JsonSlurper().parseText(secret.toString())

    then:
      deploymentConfig != null
      secret != null
      def d = secret.data

      println JsonOutput.prettyPrint(JsonOutput.toJson(secret))
      println b64d(d.name)
      println b64d(d.jdbcurl)
      println b64d(d.info)
      println b64d(d.id)
      println b64d(d."db.properties")

      secret.metadata.name == 'reference-reference-db'
      b64d(d.jdbcurl) == 'jdbc:oracle:thin:@some-db-server01.skead.no:1521/dbhotel'
  }

  private static String b64d(String d) {
    new String(d?.decodeBase64())
  }
}
