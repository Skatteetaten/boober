package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.size
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.mockk.every
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecService
import no.skatteetaten.aurora.boober.service.OpenShiftObjectGenerator
import no.skatteetaten.aurora.boober.service.internal.ApplicationDeploymentGenerator
import no.skatteetaten.aurora.boober.service.internal.Provisions
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaInstance
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhSchema
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ProvisioningResult
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultResults
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultSecretEnvResult
import no.skatteetaten.aurora.boober.utils.AbstractOpenShiftObjectGeneratorTest
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

class OpenShiftObjectGeneratorTest : AbstractOpenShiftObjectGeneratorTest() {

    lateinit var objectGenerator: OpenShiftObjectGenerator

    @BeforeEach
    fun setupTest() {
        objectGenerator = createObjectGenerator("hero")
    }

    @Test
    fun `ensure that message exist in application deployment object`() {

        val auroraConfigJson = defaultAuroraConfig()
        auroraConfigJson["utv/aos-simple.json"] = """{ "message": "Aurora <3" }"""

        val spec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
        val auroraConfigRef = AuroraConfigRef("test", "master", "123")
        val command = ApplicationDeploymentCommand(mapOf(), DEFAULT_AID, auroraConfigRef)
        val provisions = Provisions(emptyList())
        val applicationDeployment = ApplicationDeploymentGenerator.generate(spec, "123", command, "luke", provisions)
        assertThat(applicationDeployment.spec.message).isEqualTo("Aurora <3")
    }



    //TODO: reimplement
    /*
    @Test
    fun `generate rolebinding should include serviceaccount `() {

        val aid = ApplicationDeploymentRef("booberdev", "console")
        val auroraConfig = createAuroraConfig(aid, AFFILIATION, null)

        val deploymentSpec =
            AuroraDeploymentSpecService.createAuroraDeploymentSpecInternal(
                auroraConfig,
                aid
            )
        val rolebindings = objectGenerator.generateRolebindings(
            deploymentSpec.environment.permissions,
            deploymentSpec.environment.namespace
        )

        val adminRolebinding = rolebindings.find { it.at("/metadata/name").asText() == "admin" }!!
        assertThat(adminRolebinding).isNotNull()

        assertThat(
            getArray(
                adminRolebinding,
                "/userNames"
            )
        ).isEqualTo(setOf("system:serviceaccount:paas:jenkinsbuilder"))
        assertThat(getArray(adminRolebinding, "/groupNames")).isEqualTo(setOf("APP_PaaS_utv", "APP_PaaS_drift"))

        val viewRolebinding = rolebindings.find { it.at("/metadata/name").asText() == "view" }
        assertThat(viewRolebinding).isNotNull()
        assertThat(rolebindings.size).isEqualTo(2)
    }    */

    /* TOOD: Reimplement
    @Test
    fun `generate rolebinding view should split groups`() {

        val aid = ApplicationDeploymentRef("booberdev", "console")
        val auroraConfig = createAuroraConfig(aid, AFFILIATION, null)

        val deploymentSpec =
            AuroraDeploymentSpecService.createAuroraDeploymentSpecInternal(
                auroraConfig,
                aid
            )
        val rolebindings = objectGenerator.generateRolebindings(
            deploymentSpec.environment.permissions,
            deploymentSpec.environment.namespace
        )

        val adminRolebinding = rolebindings.find { it.at("/metadata/name").asText() == "admin" }!!
        assertThat(getArray(adminRolebinding, "/groupNames")).isEqualTo(setOf("APP_PaaS_utv", "APP_PaaS_drift"))

        val viewRolebinding = rolebindings.find { it.at("/metadata/name").asText() == "view" }
        assertThat(viewRolebinding).isNotNull()
        assertThat(rolebindings.size).isEqualTo(2)
    }

     */

    private fun getArray(rolebinding: JsonNode, path: String): Set<String> {
        return (rolebinding.at(path) as ArrayNode).toSet().map { it.textValue() }.toSet()
    }




}
