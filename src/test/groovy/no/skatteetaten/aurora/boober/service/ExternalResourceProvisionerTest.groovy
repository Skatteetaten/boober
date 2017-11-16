package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.model.ApplicationId.aid

import no.skatteetaten.aurora.boober.model.AbstractAuroraDeploymentSpecTest
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec

class ExternalResourceProvisionerTest extends AbstractAuroraDeploymentSpecTest {

  def "Auto providioned named schema"() {
    given:
      AuroraDeploymentSpec spec = createDeploySpecWithDbSpec('{ "database" : { "reference" : "auto" } }')

    when:
      def requests = ExternalResourceProvisioner.createSchemaProvisionRequestsFromDeploymentSpec(spec)

    then:
      requests.size() == 1
      SchemaProvisionRequest request = requests.first()
      request == new SchemaForAppRequest("aos", "utv", "reference", "reference-db")
  }

  def "Auto provisioned schema with default name"() {
    given:
      AuroraDeploymentSpec spec = createDeploySpecWithDbSpec('{ "database": true }')

    when:
      def requests = ExternalResourceProvisioner.createSchemaProvisionRequestsFromDeploymentSpec(spec)

    then:
      requests.size() == 1
      SchemaProvisionRequest request = requests.first()
      request == new SchemaForAppRequest("aos", "utv", "reference", "reference-db")
  }

  def "Named schema with explicit id"() {
    given:
      AuroraDeploymentSpec spec =
          createDeploySpecWithDbSpec('{ "database": { "reference": "fd59dba9-7d67-4ea2-bb98-081a5df8c387" } }')

    when:
      def requests = ExternalResourceProvisioner.createSchemaProvisionRequestsFromDeploymentSpec(spec)

    then:
      requests.size() == 1
      SchemaProvisionRequest request = requests.first()
      request == new SchemaIdRequest("fd59dba9-7d67-4ea2-bb98-081a5df8c387")
  }

  def "Multiple schemas"() {
    def dbSpec = '''{ 
  "database": { 
    "reference": "fd59dba9-7d67-4ea2-bb98-081a5df8c387",
    "backup": "auto" 
  } 
}'''
    given:
      AuroraDeploymentSpec spec = createDeploySpecWithDbSpec(dbSpec)

    when:
      def requests = ExternalResourceProvisioner.createSchemaProvisionRequestsFromDeploymentSpec(spec)
      println requests

    then:
      requests.size() == 2
  }

  static AuroraDeploymentSpec createDeploySpecWithDbSpec(String dbSpec) {
    createDeploymentSpec(defaultAuroraConfig().with {
      put("reference.json", REF_APP_JSON)
      put("utv/reference.json", dbSpec)
      it
    }, aid("utv", "reference"))
  }
}
