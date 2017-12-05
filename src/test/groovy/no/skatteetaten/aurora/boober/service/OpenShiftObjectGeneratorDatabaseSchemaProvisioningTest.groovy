package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.model.ApplicationId.aid
import static no.skatteetaten.aurora.boober.service.ExternalResourceProvisioner.createSchemaProvisionRequestsFromDeploymentSpec

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec

class OpenShiftObjectGeneratorDatabaseSchemaProvisioningTest extends AbstractOpenShiftObjectGeneratorTest {

  OpenShiftObjectGenerator objectGenerator = createObjectGenerator()

  def "Creates secret with database info when provisioning database"() {

    given:

      def appName = "reference"
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec([
          "about.json"        : DEFAULT_ABOUT,
          "utv/about.json"    : DEFAULT_UTV_ABOUT,
          "reference.json"    : REF_APP_JSON,
          "utv/reference.json": '''{}'''
      ], aid("utv", appName))

      def schema = new DbhSchema(
          "fd59dba9-7d67-4ea2-bb98-081a5df8c387",
          "MANAGED",
          new DatabaseInstance(1521, "some-db-server01.skead.no"),
          "jdbc:oracle:thin:@some-db-server01.skead.no:1521/dbhotel",
          [name: appName, affiliation: AFFILIATION, application: "reference", environment: "architect-utv", userId: "k72950"],
          [new DbhUser("VCLFVAPKGOMBCFTWEVKZDYBGVTMYDP", "yYGmRnUPBORxMoMcPptGvDYgKxmRSm", "SCHEMA")]
      )
      def responseText =
          loadResource(DatabaseSchemaProvisionerTest.simpleName, "schema_fd59dba9-7d67-4ea2-bb98-081a5df8c387.json")
      def responseJson = new JsonSlurper().parseText(responseText)
      def schemaInfo = responseJson.items[0]
      def expectedInfo = [database: [
          id          : schemaInfo.id,
          name        : schemaInfo.name,
          createdDate : null,
          lastUsedDate: null,
          host        : schemaInfo.databaseInstance.host,
          port        : schemaInfo.databaseInstance.port,
          service     : 'dbhotel',
          jdbcUrl     : schemaInfo.jdbcUrl,
          users       : schemaInfo.users,
          labels      : schemaInfo.labels
      ]]
      def provisioningResult = new ProvisioningResult(
          new SchemaProvisionResults([new SchemaProvisionResult(
              createSchemaProvisionRequestsFromDeploymentSpec(deploymentSpec)[0], schema, responseText
          )]))

    when:
      def objects = objectGenerator.generateApplicationObjects('deploy-id', deploymentSpec, provisioningResult)
      def deploymentConfig = objects.find { it.get("kind").textValue().toLowerCase() == "deploymentconfig" }
      def secret = objects.find { it.get("kind").textValue().toLowerCase() == "secret" }
      secret = new JsonSlurper().parseText(secret.toString())

    then:
      deploymentConfig != null
      secret != null
      def d = secret.data

      secret.metadata.name == 'reference-reference-db'
      b64d(d.jdbcurl) == 'jdbc:oracle:thin:@some-db-server01.skead.no:1521/dbhotel'
      b64d(d.name) == 'VCLFVAPKGOMBCFTWEVKZDYBGVTMYDP'
      b64d(d.id) == 'fd59dba9-7d67-4ea2-bb98-081a5df8c387'
      new JsonSlurper().parseText(b64d(d.info)) == new JsonSlurper().parseText(JsonOutput.toJson(expectedInfo))

    and: "db.properties is correct"
      def expectedProps = new Properties().with {
        load(new ByteArrayInputStream('''jdbc.url=jdbc\\:oracle\\:thin\\:@some-db-server01.skead.no\\:1521/dbhotel
jdbc.user=VCLFVAPKGOMBCFTWEVKZDYBGVTMYDP
jdbc.password=yYGmRnUPBORxMoMcPptGvDYgKxmRSm
'''.bytes)); return it
      }
      def actualProps = new Properties().
          with { load(new ByteArrayInputStream(b64d(d."db.properties").bytes)); return it }

      expectedProps == actualProps

    and: "labels are correct"
      verifyLabels(secret.metadata.labels)
  }

  boolean verifyLabels(Map<String, String> labels) {
    ['affiliation', 'app', 'booberDeployId', 'updatedBy'].every { !labels[it].isEmpty() }
  }

  private static String b64d(String d) {
    new String(d?.decodeBase64())
  }
}
