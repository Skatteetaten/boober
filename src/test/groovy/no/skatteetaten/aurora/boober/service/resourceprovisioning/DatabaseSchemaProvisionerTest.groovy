package no.skatteetaten.aurora.boober.service.resourceprovisioning

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureWebClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.test.web.client.MockRestServiceServer

import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.model.DatabaseInstance
import no.skatteetaten.aurora.boober.service.AbstractSpec
import no.skatteetaten.aurora.boober.service.ProvisioningException
import no.skatteetaten.aurora.boober.service.SpringTestUtils
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.internal.SharedSecretReader
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaForAppRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaIdRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionResults

@WithUserDetails("aurora")
@AutoConfigureWebClient
@SpringBootTest(classes = [
    Configuration,
    SharedSecretReader,
    SpringTestUtils.SecurityMock,
    DatabaseSchemaProvisioner,
    UserDetailsProvider,
    SpringTestUtils.AuroraMockRestServiceServiceInitializer
])
class DatabaseSchemaProvisionerTest extends AbstractSpec {

  public static final String DBH_HOST = "http://localhost:8080"

  @Autowired
  MockRestServiceServer dbhServer

  @Autowired
  DatabaseSchemaProvisioner provisioner

  def databaseInstance = new DatabaseInstance(null, true, [affiliation: "aos"])
  def id = "fd59dba9-7d67-4ea2-bb98-081a5df8c387"
  def appName = "reference"
  def schemaName = "reference"

  def labels = [affiliation: 'aos', environment: 'aos-utv', application: appName, name: schemaName]

  def details = new SchemaRequestDetails(
      schemaName,
      [new SchemaUser("SCHEMA", "a", "aos")],
      DatabaseEngine.ORACLE,
      "aos", databaseInstance)

  def "Schema request with id succeeds when schema exists"() {

    given:
      dbhServer.expect(requestTo("${DBH_HOST}/api/v1/schema/$id?affiliation=aos&roles=SCHEMA")).
          andRespond(withSuccess(loadResource("schema_${id}.json"), MediaType.APPLICATION_JSON))

    when:
      def provisionResult = provisioner.provisionSchemas([new SchemaIdRequest(id, details)])

    then:
      assertSchemaIsCorrect(provisionResult)
  }

  def "Schema request with id fails when schema does not exist"() {

    given:
      dbhServer.expect(requestTo("${DBH_HOST}/api/v1/schema/$id?affiliation=aos&roles=SCHEMA")).
          andRespond(withStatus(HttpStatus.NOT_FOUND)
              .body(loadResource("schema_${id}_not_found.json"))
              .contentType(MediaType.APPLICATION_JSON))

    when:

      provisioner.provisionSchemas([new SchemaIdRequest(id, details)])

    then:
      thrown(ProvisioningException)
  }

  def "Matching of application coordinates to schema"() {

    given:
      def labelsString = labels.collect { k, v -> "$k%3D$v" }.join(",")
      dbhServer.expect(requestTo("${DBH_HOST}/api/v1/schema/?labels=$labelsString&roles=SCHEMA&engine=ORACLE")).
          andRespond(withSuccess(loadResource("schema_${id}.json"), MediaType.APPLICATION_JSON))

    when:
      def provisionResult = provisioner.
          provisionSchemas([new SchemaForAppRequest("utv", "reference", true, details)])

    then:
      assertSchemaIsCorrect(provisionResult)
  }

  def "Creates new schema if schema is missing"() {

    given:
      def labelsString = labels.collect { k, v -> "$k%3D$v" }.join(",")
      def createBody = new SchemaRequestPayload(
          labels + [userId: 'aurora'],
          details.users,
          details.engine,
          details.databaseInstance.labels,
          details.databaseInstance.name,
          details.databaseInstance.fallback)

      def body = new ObjectMapper().writeValueAsString(createBody)
      dbhServer.expect(requestTo("${DBH_HOST}/api/v1/schema/?labels=$labelsString&roles=SCHEMA&engine=ORACLE")).
          andRespond(withSuccess(loadResource("schema_empty_response.json"), MediaType.APPLICATION_JSON))

      dbhServer.expect(requestTo("${DBH_HOST}/api/v1/schema/")).
          andExpect(method(HttpMethod.POST)).
          andExpect(content().string(body)).
          andRespond(withSuccess(loadResource("schema_${id}.json"), MediaType.APPLICATION_JSON))

    when:
      def provisionResult = provisioner.
          provisionSchemas([new SchemaForAppRequest("utv", "reference", true, details)])

    then:
      assertSchemaIsCorrect(provisionResult)
  }

  private static assertSchemaIsCorrect(SchemaProvisionResults provisionResult) {

    def results = provisionResult.results
    assert results.size() == 1
    def schema = results[0].dbhSchema
    assert schema.jdbcUrl == 'jdbc:oracle:thin:@some-db-server01.skead.no:1521/dbhotel'
    assert schema.username == 'VCLFVAPKGOMBCFTWEVKZDYBGVTMYDP'
    assert schema.password == 'yYGmRnUPBORxMoMcPptGvDYgKxmRSm'
    true
  }
}
