package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.Test
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest

class ConfigFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = ConfigFeature()

    val auroraConfigAppFile = """{ 
               "config": {
                 "JSON_ARRAY" : "[ { uri: \"http://tsl0part-fk1-s-adm01:20000/registry\", urn: [\"urn:skatteetaten:part:identifikasjon:partsidentifikasjon:root\"], segment: \"part\" }, { uri: \"http://tsl0part-fk1-s-adm01:20000/registry\", urn: [\"urn:skatteetaten:part:partsregister:feed:*\"], segment: \"part\" } , { uri: \"http://tsl0part-fk1-s-adm01:20000/registry\", urn: [\"urn:skatteetaten:part:partsregister:hendelselager:*\"], segment: \"part\" } , { uri: \"http://tsl0part-fk1-s-adm01:20000/registry\", urn: [\"no:skatteetaten:sikkerhet:tilgangskontroll:ats:v1\"], segment: \"part\" } ]",
                 "STRING": "Hello",
                 "BOOL": false,
                 "INT": 42,
                 "FLOAT": 4.2,
                 "ARRAY": [4.2, "STRING", true],
                 "URL": "https://int-at.skead.no:13110/felles/sikkerhet/stsSikkerhet/v1/validerSaml",
                 "JSON_STRING": "{\"key\": \"value\"}"                  
                }
           }"""

    @Test
    fun `modify dc and add config`() {

        val resources = modifyResources(
            auroraConfigAppFile, createEmptyDeploymentConfig()
        )

        val dcResource = resources.first()

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added env vars")
        val dc = dcResource.resource as DeploymentConfig

        val env = dc.spec.template.spec.containers.first().env.associate { it.name to it.value }

        assertThat(env["JSON_ARRAY"]).isEqualTo("""[ { uri: "http://tsl0part-fk1-s-adm01:20000/registry", urn: ["urn:skatteetaten:part:identifikasjon:partsidentifikasjon:root"], segment: "part" }, { uri: "http://tsl0part-fk1-s-adm01:20000/registry", urn: ["urn:skatteetaten:part:partsregister:feed:*"], segment: "part" } , { uri: "http://tsl0part-fk1-s-adm01:20000/registry", urn: ["urn:skatteetaten:part:partsregister:hendelselager:*"], segment: "part" } , { uri: "http://tsl0part-fk1-s-adm01:20000/registry", urn: ["no:skatteetaten:sikkerhet:tilgangskontroll:ats:v1"], segment: "part" } ]""")
        assertThat(env["STRING"]).isEqualTo("Hello")
        assertThat(env["BOOL"]).isEqualTo("false")
        assertThat(env["INT"]).isEqualTo("42")
        assertThat(env["FLOAT"]).isEqualTo("4.2")
        assertThat(env["ARRAY"]).isEqualTo("""[4.2,"STRING",true]""")
        assertThat(env["JSON_STRING"]).isEqualTo("""{"key": "value"}""")
        assertThat(env["URL"]).isEqualTo("""https://int-at.skead.no:13110/felles/sikkerhet/stsSikkerhet/v1/validerSaml""")
    }

    @Test
    fun `test render spec`() {

        val spec = createAuroraDeploymentSpecForFeature(auroraConfigAppFile)

        assertThat(spec).auroraDeploymentSpecMatches("spec-default.json")
    }
}
