package no.skatteetaten.aurora.boober.service

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer

import no.skatteetaten.aurora.boober.Configuration

@RestClientTest
@SpringBootTest(classes = [
    Configuration,
    DatabaseSchemaProvisioner,
    AbstractAuroraDeploymentSpecSpringTest.MockRestServiceServiceInitializer
])
class DatabaseSchemaProvisionerTest extends AbstractSpec {

  public static final String DBH_HOST = "http://localhost:8080"

  @Autowired
  MockRestServiceServer dbhServer

  @Autowired
  DatabaseSchemaProvisioner provisioner

  def id = "fd59dba9-7d67-4ea2-bb98-081a5df8c387"

  def "Schema request with id succeeds when schema exists"() {

    given:
      dbhServer.expect(requestTo("${DBH_HOST}/api/v1/schema/$id")).
          andRespond(withSuccess(loadResource("schema_${id}.json"), MediaType.APPLICATION_JSON))

    when:
      def provisionResult = provisioner.provisionSchemas([new SchemaIdRequest(id)])

    then:
      provisionResult.results.size() == 1
      provisionResult.results[0].dbhSchema.jdbcUrl
  }

  def "Schema request with id fails when schema does not exist"() {

    given:
      dbhServer.expect(requestTo("${DBH_HOST}/api/v1/schema/$id")).
          andRespond(withStatus(HttpStatus.NOT_FOUND)
              .body(loadResource("schema_${id}_not_found.json"))
              .contentType(MediaType.APPLICATION_JSON))

    when:
      provisioner.provisionSchemas([new SchemaIdRequest(id)])

    then:
      thrown(ProvisioningException)
  }

  def a() {

    given:
      dbhServer.expect(requestTo("${DBH_HOST}/api/v1/schema/$id")).
          andRespond(withStatus(HttpStatus.NOT_FOUND)
              .body(loadResource("schema_${id}.json"))
              .contentType(MediaType.APPLICATION_JSON))

    when:
      provisioner.provisionSchemas([new SchemaForAppRequest(id)])

    then:
      thrown(ProvisioningException)
  }
}
