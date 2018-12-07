package no.skatteetaten.aurora.boober.service.resourceprovisioning

import static no.skatteetaten.aurora.boober.mapper.v1.DatabaseFlavor.ORACLE_MANAGED
import static no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef.aid

import no.skatteetaten.aurora.boober.mapper.v1.DatabasePermission
import no.skatteetaten.aurora.boober.model.AbstractAuroraDeploymentSpecTest
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal

class ExternalResourceProvisionerTest extends AbstractAuroraDeploymentSpecTest {

  def details = new SchemaRequestDetails("reference", [:], [SCHEMA: DatabasePermission.ALL], [:], ORACLE_MANAGED, "aos")

  def "Auto provisioned named schema"() {
    given:
      AuroraDeploymentSpecInternal spec = createDeploySpecWithDbSpec('{ "database" : { "REFerence" : "auto" } }')

    when:
      def requests = ExternalResourceProvisioner.createSchemaProvisionRequestsFromDeploymentSpec(spec)

    then:
      requests.size() == 1
      SchemaProvisionRequest request = requests.first()
      request == new SchemaForAppRequest("utv", "reference", true, details)
  }

  def "Auto provisioned schema with default name"() {
    given:
      AuroraDeploymentSpecInternal spec = createDeploySpecWithDbSpec('{ "database": true }')

    when:
      def requests = ExternalResourceProvisioner.createSchemaProvisionRequestsFromDeploymentSpec(spec)

    then:
      requests.size() == 1
      SchemaProvisionRequest request = requests.first()
      request == new SchemaForAppRequest("utv", "reference", true, details)
  }

  def "Named schema with explicit id"() {
    given:
      AuroraDeploymentSpecInternal spec =
          createDeploySpecWithDbSpec('{ "database": { "REFerence": "fd59dba9-7d67-4ea2-bb98-081a5df8c387" } }')

    when:
      def requests = ExternalResourceProvisioner.createSchemaProvisionRequestsFromDeploymentSpec(spec)

    then:
      requests.size() == 1
      SchemaProvisionRequest request = requests.first()
      request == new SchemaIdRequest("fd59dba9-7d67-4ea2-bb98-081a5df8c387", details)
  }

  def "Multiple schemas"() {
    def dbSpec = '''{ 
  "database": { 
    "REFerence": "fd59dba9-7d67-4ea2-bb98-081a5df8c387",
    "backup": "auto" 
  } 
}'''
    given:
      AuroraDeploymentSpecInternal spec = createDeploySpecWithDbSpec(dbSpec)

    when:
      def requests = ExternalResourceProvisioner.createSchemaProvisionRequestsFromDeploymentSpec(spec)

    then:
      requests.size() == 2
  }

  static AuroraDeploymentSpecInternal createDeploySpecWithDbSpec(String dbSpec) {
    createDeploymentSpec(defaultAuroraConfig().with {
      put("reference.json", REF_APP_JSON)
      put("utv/reference.json", dbSpec)
      it
    }, aid("utv", "reference"))
  }
}
