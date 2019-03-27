package no.skatteetaten.aurora.boober.service

import assertk.assertThat
import assertk.assertions.containsAll
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.readValue
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef.Companion.adr
import no.skatteetaten.aurora.boober.service.internal.DbhSecretGenerator
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaInstance
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisionerTest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhSchema
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhUser
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ExternalResourceProvisioner.Companion.createSchemaProvisionRequestsFromDeploymentSpec
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ProvisioningResult
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionResult
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionResults
import no.skatteetaten.aurora.boober.utils.base64Decode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.Properties

class OpenShiftObjectGeneratorDatabaseSchemaProvisioningTest : AbstractOpenShiftObjectGeneratorTest() {

    lateinit var objectGenerator: OpenShiftObjectGenerator

    @BeforeEach
    fun setupTest() {
        objectGenerator = createObjectGenerator()
    }

    @Test
    fun `Creates secret with database info when provisioning database`() {

        val appName = "reference"
        val deploymentSpec = createDeploymentSpec(
            mapOf(
                "about.json" to DEFAULT_ABOUT,
                "utv/about.json" to DEFAULT_UTV_ABOUT,
                "reference.json" to REF_APP_JSON_LONG_DB_NAME,
                "utv/reference.json" to """{}"""
            ), adr("utv", appName)
        )

        val schema = DbhSchema(
            id = "fd59dba9-7d67-4ea2-bb98-081a5df8c387",
            type = "MANAGED",
            databaseInstance = DatabaseSchemaInstance(1521, "some-db-server01.skead.no"),
            jdbcUrl = "jdbc:oracle:thin:@some-db-server01.skead.no:1521/dbhotel",
            labels = mapOf(
                "name" to appName,
                "affiliation" to AFFILIATION,
                "application" to "reference",
                "environment" to "architect-utv", "userId" to "k72950"
            ),
            users = listOf(DbhUser("VCLFVAPKGOMBCFTWEVKZDYBGVTMYDP", "yYGmRnUPBORxMoMcPptGvDYgKxmRSm", "SCHEMA"))
        )

        val responseText = loadResource(
            "schema_fd59dba9-7d67-4ea2-bb98-081a5df8c387.json",
            "resourceprovisioning/" + DatabaseSchemaProvisionerTest::class.java.simpleName
        )

        val responseJson: DatabaseSchemaProvisioner.DbApiEnvelope = mapper.readValue(responseText)

        val schemaInfo = responseJson.items[0]
        val infoFile = DbhSecretGenerator.createInfoFile(schemaInfo)

        val provisioningResult = ProvisioningResult(
            schemaProvisionResults = SchemaProvisionResults(
                results = listOf(
                    SchemaProvisionResult(
                        request = createSchemaProvisionRequestsFromDeploymentSpec(deploymentSpec)[0],
                        dbhSchema = schema,
                        responseText = responseText
                    )
                )
            ),
            vaultResults = null,
            stsProvisioningResult = null
        )

        val objects = objectGenerator.generateApplicationObjects(
            "deploy-id",
            deploymentSpec,
            provisioningResult,
            OwnerReference()
        )
        val dcResource = objects.find { it.get("kind").textValue().toLowerCase() == "deploymentconfig" }!!

        val deploymentConfig = mapper.convertValue<DeploymentConfig>(dcResource)
        assertThat(deploymentConfig).isNotNull()

        val secretResource = objects.find { it.get("kind").textValue().toLowerCase() == "secret" }!!
        val secret: Secret = mapper.convertValue(secretResource)
        assertThat(secret).isNotNull()

        assertThat(secret.metadata.name).isEqualTo("reference-reference-name-db")

        val container = deploymentConfig.spec.template.spec.containers[0]
        val volumes = deploymentConfig.spec.template.spec.volumes
        val volumeMount = container.volumeMounts.find { it.name == "reference-name-db" }
        assertThat(volumeMount).isNotNull()

        assertThat(volumes.find { it.name == volumeMount?.name && it.secret.secretName == secret.metadata.name }).isNotNull()

        val d = secret.data
        assertThat(d["jdbcurl"]?.base64Decode()).isEqualTo("jdbc:oracle:thin:@some-db-server01.skead.no:1521/dbhotel")

        assertThat(d["name"]?.base64Decode()).isEqualTo("VCLFVAPKGOMBCFTWEVKZDYBGVTMYDP")
        assertThat(d["id"]?.base64Decode()).isEqualTo("fd59dba9-7d67-4ea2-bb98-081a5df8c387")

        val info = d["info"]?.base64Decode()!!

        assertThat(mapper.readValue<JsonNode>(info)).isEqualTo(mapper.readValue<JsonNode>(infoFile))

        val expectedProps = Properties().apply {
            load(
                ByteArrayInputStream(
                    """jdbc.url=jdbc:oracle:thin:@some-db-server01.skead.no:1521/dbhotel
            jdbc.user=VCLFVAPKGOMBCFTWEVKZDYBGVTMYDP
            jdbc.password=yYGmRnUPBORxMoMcPptGvDYgKxmRSm
            """.toByteArray()
                )
            )
        }
        val actualProps =
            Properties().apply { load(ByteArrayInputStream(d["db.properties"]?.base64Decode()?.toByteArray())) }

        assertThat(expectedProps).isEqualTo(actualProps)

        assertThat(secret.metadata.labels.keys).containsAll("affiliation", "app", "booberDeployId", "updatedBy")
    }
}
