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
          "utv/reference.json": '''{ "certificate": false }'''
      ], aid("utv", "reference"))

      def provisioningResult = new ProvisioningResult(
          new SchemaProvisionResults([new SchemaProvisionResult(
              createSchemaProvisionRequestsFromDeploymentSpec(deploymentSpec)[0],
              new DbhSchema("", "", "", [:], []), "")
          ]))

    when:
      def dc = objectGenerator.generateDeploymentConfig("deploy-id", deploymentSpec, provisioningResult)
      dc = new JsonSlurper().parseText(dc.toString()) // convert to groovy for easier navigation and validation

    then:
      def name = deploymentSpec.name
      def nameUpper = name.toUpperCase()

      def spec = dc.spec.template.spec
      spec.volumes.find { it == [name: "$name-db", secret: [secretName: "$name-db"]] }
      def container = spec.containers[0]
      container.volumeMounts.find { it == [mountPath: "/u01/secrets/app/$name-db", name: "$name-db"] }
      def expectedEnvs = [
          ("${nameUpper}_DB")           : "/u01/secrets/app/${name}-db/info",
          ("${nameUpper}_DB_PROPERTIES"): "/u01/secrets/app/${name}-db/db.properties",
          DB                            : "/u01/secrets/app/${name}-db/info",
          DB_PROPERTIES                 : "/u01/secrets/app/${name}-db/db.properties",
          ("VOLUME_${nameUpper}_DB")    : "/u01/secrets/app/${name}-db"
      ]
      expectedEnvs.every { envName, envValue -> container.env.find { it.name == envName && it.value == envValue } }
  }
}
