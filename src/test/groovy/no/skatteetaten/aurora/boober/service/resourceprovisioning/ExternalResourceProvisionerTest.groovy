package no.skatteetaten.aurora.boober.service.resourceprovisioning

import static no.skatteetaten.aurora.boober.model.ApplicationId.aid

import no.skatteetaten.aurora.boober.model.AbstractAuroraDeploymentSpecTest
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ExternalResourceProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaForAppRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaIdRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionRequest

class ExternalResourceProvisionerTest extends AbstractAuroraDeploymentSpecTest {

  def "Auto provisioned named schema"() {
    given:
      AuroraDeploymentSpec spec = createDeploySpecWithDbSpec('{ "database" : { "REFerence" : "auto" } }')

    when:
      def requests = ExternalResourceProvisioner.createSchemaProvisionRequestsFromDeploymentSpec(spec)

    then:
      requests.size() == 1
      SchemaProvisionRequest request = requests.first()
      request == new SchemaForAppRequest("aos", "utv", "reference", "reference")
  }

  def "Auto provisioned schema with default name"() {
    given:
      AuroraDeploymentSpec spec = createDeploySpecWithDbSpec('{ "database": true }')

    when:
      def requests = ExternalResourceProvisioner.createSchemaProvisionRequestsFromDeploymentSpec(spec)

    then:
      requests.size() == 1
      SchemaProvisionRequest request = requests.first()
      request == new SchemaForAppRequest("aos", "utv", "reference", "reference")
  }

  def "Named schema with explicit id"() {
    given:
      AuroraDeploymentSpec spec =
          createDeploySpecWithDbSpec('{ "database": { "REFerence": "fd59dba9-7d67-4ea2-bb98-081a5df8c387" } }')

    when:
      def requests = ExternalResourceProvisioner.createSchemaProvisionRequestsFromDeploymentSpec(spec)

    then:
      requests.size() == 1
      SchemaProvisionRequest request = requests.first()
      request == new SchemaIdRequest("fd59dba9-7d67-4ea2-bb98-081a5df8c387", "reference")
  }

  def "Multiple schemas"() {
    def dbSpec = '''{ 
  "database": { 
    "REFerence": "fd59dba9-7d67-4ea2-bb98-081a5df8c387",
    "backup": "auto" 
  } 
}'''
    given:
      AuroraDeploymentSpec spec = createDeploySpecWithDbSpec(dbSpec)

    when:
      def requests = ExternalResourceProvisioner.createSchemaProvisionRequestsFromDeploymentSpec(spec)

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
