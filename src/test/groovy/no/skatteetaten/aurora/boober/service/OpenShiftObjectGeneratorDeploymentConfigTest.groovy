package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.model.ApplicationId.aid
import static no.skatteetaten.aurora.boober.service.ExternalResourceProvisioner.createSchemaProvisionRequestsFromDeploymentSpec

import groovy.json.JsonSlurper
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec

class OpenShiftObjectGeneratorDeploymentConfigTest extends AbstractOpenShiftObjectGeneratorTest {

  OpenShiftObjectGenerator objectGenerator = createObjectGenerator()

  def "Creates volumes, volumeMounts and env vars for provisioned database"() {

    given:

      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec([
          "about.json"        : DEFAULT_ABOUT,
          "utv/about.json"    : DEFAULT_UTV_ABOUT,
          "reference.json"    : REF_APP_JSON,
          "utv/reference.json": '''{ "name": "something-different", "certificate": false }'''
      ], aid("utv", "reference"))

      def provisioningResult = new ProvisioningResult(
          new SchemaProvisionResults([new SchemaProvisionResult(
              createSchemaProvisionRequestsFromDeploymentSpec(deploymentSpec)[0],
              new DbhSchema("", "", new DatabaseInstance(1512, ""), "", [:], []), "")
          ]))

    when:
      def dc = objectGenerator.generateDeploymentConfig("deploy-id", deploymentSpec, provisioningResult)
      dc = new JsonSlurper().parseText(dc.toString()) // convert to groovy for easier navigation and validation

    then:
      def appName = deploymentSpec.name
      def dbName = deploymentSpec.deploy.database.first().name.toLowerCase()
      def dbNameUpper = dbName.toUpperCase()

      def spec = dc.spec.template.spec
      spec.volumes.find { it == [name: "$dbName-db", secret: [secretName: "$appName-$dbName-db"]] }
      def container = spec.containers[0]
      container.volumeMounts.find { it == [mountPath: "/u01/secrets/app/$dbName-db", name: "$dbName-db"] }
      def expectedEnvs = [
          ("${dbNameUpper}_DB")           : "/u01/secrets/app/${dbName}-db/info",
          ("${dbNameUpper}_DB_PROPERTIES"): "/u01/secrets/app/${dbName}-db/db.properties",
          DB                              : "/u01/secrets/app/${dbName}-db/info",
          DB_PROPERTIES                   : "/u01/secrets/app/${dbName}-db/db.properties",
          ("VOLUME_${dbNameUpper}_DB")    : "/u01/secrets/app/${dbName}-db"
      ]
      expectedEnvs.every { envName, envValue -> container.env.find { it.name == envName && it.value == envValue } }
  }
}
