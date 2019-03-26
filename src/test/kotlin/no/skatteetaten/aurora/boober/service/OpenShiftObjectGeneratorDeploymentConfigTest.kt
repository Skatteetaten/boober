package no.skatteetaten.aurora.boober.service

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef.Companion.aid
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaInstance
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhSchema
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ExternalResourceProvisioner.Companion.createSchemaProvisionRequestsFromDeploymentSpec
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ProvisioningResult
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionResult
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionResults
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OpenShiftObjectGeneratorDeploymentConfigTest : AbstractOpenShiftObjectGeneratorTest() {

    lateinit var objectGenerator: OpenShiftObjectGenerator

    @BeforeEach
    fun setupTest() {
        objectGenerator = createObjectGenerator()
    }

    @Test
    fun `Creates volumes, volumeMounts and env vars for provisioned database`() {

        val deploymentSpec = createDeploymentSpec(
            mutableMapOf(
                "about.json" to DEFAULT_ABOUT,
                "utv/about.json" to DEFAULT_UTV_ABOUT,
                "reference.json" to REF_APP_JSON,
                "utv/reference.json" to """{ "name": "something-different", "certificate": false }"""
            ), aid("utv", "reference")
        )

        val provisioningResult = ProvisioningResult(
            schemaProvisionResults = SchemaProvisionResults(
                listOf(
                    SchemaProvisionResult(
                        request = createSchemaProvisionRequestsFromDeploymentSpec(deploymentSpec)[0],
                        dbhSchema = DbhSchema(
                            id = "",
                            type = "",
                            databaseInstance = DatabaseSchemaInstance(1512, ""),
                            jdbcUrl = "",
                            labels = emptyMap(),
                            users = emptyList()
                        ),
                        responseText = ""
                    )
                )
            ), vaultResults = null, stsProvisioningResult = null
        )

        val dcResource = objectGenerator.generateDeploymentConfig(
            "deploy-id",
            deploymentSpec,
            provisioningResult,
            OwnerReference()
        )!!
        val dc: DeploymentConfig = mapper.convertValue(dcResource)

        val appName = deploymentSpec.name
        val dbName = deploymentSpec.integration?.database?.first()?.name?.toLowerCase()
        val dbNameEnv = dbName?.toUpperCase()?.replace("-", "_")
        val spec = dc.spec.template.spec

        assertThat(spec.volumes.find { it.name == "$dbName-db" && it.secret.secretName == "$appName-$dbName-db" }).isNotNull()

        val container = spec.containers[0]
        assertThat(container.volumeMounts.find { it.mountPath == "/u01/secrets/app/$dbName-db" && it.name == "$dbName-db" }).isNotNull()

        val expectedEnvs = mapOf(

            "${dbNameEnv}_DB" to "/u01/secrets/app/$dbName-db/info",
            "${dbNameEnv}_DB_PROPERTIES" to "/u01/secrets/app/$dbName-db/db.properties",
            "DB" to "/u01/secrets/app/$dbName-db/info",
            "DB_PROPERTIES" to "/u01/secrets/app/$dbName-db/db.properties",
            "VOLUME_${dbNameEnv}_DB" to "/u01/secrets/app/$dbName-db"
        )

        expectedEnvs.forEach { env ->
            container.env.find { it.name == env.key && it.value == env.value }
        }
    }

    @Test
    fun `toxiproxy sidecar must be created if toxiproxy is enabled in deployment spec for java`() {

        val name = "reference-toxiproxy"
        val deploymentSpec = specJavaWithToxiproxy()
        val provisioningResult = provisiongResult(deploymentSpec)

        val dc =
            objectGenerator.generateDeploymentConfig("deploy-id", deploymentSpec, provisioningResult, OwnerReference())

        dcContainsValidToxiProxyContainer(dc, name)
        dcContainsValidToxiProxyVolume(dc, name)
    }

    @Test
    fun `toxiproxy sidecar must be created if toxiproxy is enabled in deployment spec for web`() {

        val name = "webleveranse-toxiproxy"
        val deploymentSpec = specWebWithToxiproxy()
        val provisioningResult = provisiongResult(deploymentSpec)

        val dc =
            objectGenerator.generateDeploymentConfig("deploy-id", deploymentSpec, provisioningResult, OwnerReference())

        dcContainsValidToxiProxyContainer(dc, name)
        dcContainsValidToxiProxyVolume(dc, name)
    }

    fun dcContainsValidToxiProxyContainer(dcNode: JsonNode?, name: String) {
        val dc: DeploymentConfig = mapper.convertValue(dcNode!!)

        val container = dc.spec.template.spec.containers.find { it.name == name }!!

        assertThat(container).isNotNull()
        assertThat(container.image).isEqualTo("shopify/toxiproxy:2.1.3")
        assertThat(container.args).isEqualTo(listOf("-config", "/u01/config/config.json"))
        assertThat(container.volumeMounts.find { it.mountPath == "/u01/config" && it.name == "toxiproxy-volume" }).isNotNull()
    }

    fun dcContainsValidToxiProxyVolume(dcNode: JsonNode?, name: String) {

        val dc: DeploymentConfig = mapper.convertValue(dcNode!!)
        val volume = dc.spec.template.spec.volumes.find { it.name == "toxiproxy-volume" }!!
        assertThat(volume).isNotNull()
        assertThat(volume.configMap.name == "$name-config")
    }

    fun provisiongResult(deploymentSpec: AuroraDeploymentSpecInternal): ProvisioningResult {
        return ProvisioningResult(
            schemaProvisionResults = SchemaProvisionResults(
                results = listOf(
                    SchemaProvisionResult(
                        request = createSchemaProvisionRequestsFromDeploymentSpec(deploymentSpec)[0],
                        dbhSchema = DbhSchema(
                            id = "",
                            type = "",
                            databaseInstance = DatabaseSchemaInstance(1512, ""),
                            jdbcUrl = "",
                            labels = emptyMap(),
                            users = emptyList()
                        ), responseText = ""
                    )
                )
            ), vaultResults = null, stsProvisioningResult = null
        )
    }
}
