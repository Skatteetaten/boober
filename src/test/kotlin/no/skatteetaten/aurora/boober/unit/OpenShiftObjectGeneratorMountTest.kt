package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.module.kotlin.convertValue
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.Secret
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef.Companion.adr
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultSecretEnvResult
import no.skatteetaten.aurora.boober.utils.AbstractOpenShiftObjectGeneratorTest
import no.skatteetaten.aurora.boober.utils.base64Decode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OpenShiftObjectGeneratorMountTest : AbstractOpenShiftObjectGeneratorTest() {

    /*
    Test edge cases
    @Test
    fun `Verify properties entries contains a line for each property`() {

        val auroraConfigJson = mutableMapOf(
            "about.json" to DEFAULT_ABOUT,
            "utv/about.json" to DEFAULT_UTV_ABOUT,
            "aos-simple.json" to AOS_SIMPLE_JSON,
            "utv/aos-simple.json" to """{
        "config": {
            "OPPSLAGSTJENESTE_DELEGERING" : "[ { uri: \"http://tsl0part-fk1-s-adm01:20000/registry\", urn: [\"urn:skatteetaten:part:identifikasjon:partsidentifikasjon:root\"], segment: \"part\" }, { uri: \"http://tsl0part-fk1-s-adm01:20000/registry\", urn: [\"urn:skatteetaten:part:partsregister:feed:*\"], segment: \"part\" } , { uri: \"http://tsl0part-fk1-s-adm01:20000/registry\", urn: [\"urn:skatteetaten:part:partsregister:hendelselager:*\"], segment: \"part\" } , { uri: \"http://tsl0part-fk1-s-adm01:20000/registry\", urn: [\"no:skatteetaten:sikkerhet:tilgangskontroll:ats:v1\"], segment: \"part\" } ]",
            "UTSTED_SAML_URL" : "https://int-at.skead.no:13110/felles/sikkerhet/stsSikkerhet/v1/utstedSaml",
            "VALIDER_SAML_URL" : "https://int-at.skead.no:13110/felles/sikkerhet/stsSikkerhet/v1/validerSaml",
            "1": {
            "OPPSLAGSTJENESTE_DELEGERING" : "[ { uri: \"http://tsl0part-fk1-s-adm02:20000/registry\", urn: [\"urn:skatteetaten:part:identifikasjon:partsidentifikasjon:root\"], segment: \"part\" } ]",
            "VALIDER_SAML_URL" : "https://int-at2.skead.no:13110/felles/sikkerhet/stsSikkerhet/v1/validerSaml"
        }
        }
    }"""

        )

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, adr("utv", "aos-simple"))

        val jsonMounts = objectGenerator.generateSecretsAndConfigMapsInTest(
            "deploy-id", deploymentSpec, null, deploymentSpec.name,
            OwnerReference()
        )!!

        assertThat(jsonMounts.size).isEqualTo(1)
        val mount = jsonMounts.first()

        deploymentSpec.env.containsKey("OPPSLAGSTJENESTE_DELEGERING")
        deploymentSpec.env.containsKey("UTSTED_SAML_URL")
        deploymentSpec.env.containsKey("VALIDER_SAML_URL")

        val propertiesFile = mount.get("data").get("1.properties").textValue()
        val lines = propertiesFile.lines()

        val zipped = lines.zip(listOf("OPPSLAGSTJENESTE_DELEGERING", "VALIDER_SAML_URL"))

        zipped.forEach {
            assertThat(it.first).startsWith("${it.second}=")
            assertThat(it.first.startsWith("${it.second}=null")).isFalse()
        }
    }

    @Test
    fun `Renders non String configs properly`() {

        val auroraConfigJson = defaultAuroraConfig()
        auroraConfigJson["utv/aos-simple.json"] = """{
    "config": {
        "STRING": "Hello",
        "BOOL": false,
        "INT": 42,
        "FLOAT": 4.2,
        "ARRAY": [4.2, "STRING", true],
        "URL": "https://int-at.skead.no:13110/felles/sikkerhet/stsSikkerhet/v1/validerSaml",
        "JSON_STRING": "{\"key\": \"value\"}"
    }
}"""
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
        val env = deploymentSpec.env

        assertThat(env["STRING"]).isEqualTo("Hello")
        assertThat(env["BOOL"]).isEqualTo("false")
        assertThat(env["INT"]).isEqualTo("42")
        assertThat(env["FLOAT"]).isEqualTo("4.2")
        assertThat(env["ARRAY"]).isEqualTo("""[4.2,"STRING",true]""")
        assertThat(env["JSON_STRING"]).isEqualTo("""{"key": "value"}""")
        assertThat(env["URL"]).isEqualTo("""https://int-at.skead.no:13110/felles/sikkerhet/stsSikkerhet/v1/validerSaml""")
    }

    @Test
    fun `Creates secret from secretVault`() {

        val auroraConfigJson = mutableMapOf(
            "about.json" to DEFAULT_ABOUT,
            "utv/about.json" to DEFAULT_UTV_ABOUT,
            "aos-simple.json" to AOS_SIMPLE_JSON,
            "utv/aos-simple.json" to """{
    "certificate": false,
    "secretVault": "test"
}"""
        )

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, adr("utv", "aos-simple"))
        val propertyValue = "BAR"
        val propertyKey = "FOO"
        val provisioningResult = ProvisioningResult(
            vaultSecretEnvResult = listOf(
                VaultSecretEnvResult(
                    name = "aos-simple-test",
                    secrets = mapOf(propertyKey to propertyValue.toByteArray())
                )
            )
        )

        val jsonMounts = objectGenerator.generateSecretsAndConfigMapsInTest(
            deployId = "deploy-id",
            deploymentSpecInternal = deploymentSpec,
            provisioningResult = provisioningResult,
            name = deploymentSpec.name,
            ownerReference = OwnerReference()
        )!!

        assertThat(jsonMounts.size).isEqualTo(1)
        val mount = mapper.convertValue<Secret>(jsonMounts.first())
        assertThat(mount.kind).isEqualTo("Secret")
        assertThat(mount.metadata.name).isEqualTo("${deploymentSpec.name}-test")
        assertThat(mount.data[propertyKey]?.base64Decode()).isEqualTo(propertyValue)
    }

     */
}
