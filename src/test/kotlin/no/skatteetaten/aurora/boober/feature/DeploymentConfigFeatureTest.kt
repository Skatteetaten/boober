package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.Test
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isSuccess
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError

class DeploymentConfigFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = DeploymentConfigFeature()

    @Test
    fun `should allow objects if validation is boolean and not required`() {
        assertThat {
            createAuroraConfigFieldHandlers(
                """{ 
                "groupId": "no.skatteetaten.aurora",
                    "version" : "1",
                    "management" : {
                      "path" : "/foo"
                    }
               }"""
            )
        }.isSuccess()
    }

    @Test
    fun `should not allowed latest for releaseTo`() {
        assertThat {
            createAuroraConfigFieldHandlers(
                """{ 
                "groupId": "no.skatteetaten.aurora",
                "version" : "1",
                "releaseTo": "latest"
               }"""
            )
        }.singleApplicationError("Disallowed")
    }

    @Test
    fun `should allow other tags for releaseTo`() {
        assertThat {
            createAuroraConfigFieldHandlers(
                """{ 
                "groupId": "no.skatteetaten.aurora",
                "version" : "1",
                "releaseTo": "PROD"
               }"""
            )
        }.isSuccess()
    }

    @Test
    fun `should not allow semantic version for releaseTo`() {
        assertThat {
            createAuroraConfigFieldHandlers(
                """{ 
                "groupId": "no.skatteetaten.aurora",
                "version" : "1",
                "releaseTo": "1.2.3"
               }"""
            )
        }.singleApplicationError("Disallowed")
    }

    @Test
    fun `should not allow numbered version for releaseTo`() {
        assertThat {
            createAuroraConfigFieldHandlers(
                """{ 
                "groupId": "no.skatteetaten.aurora",
                "version" : "1",
                "releaseTo": "123"
               }"""
            )
        }.singleApplicationError("Disallowed")
    }

    @Test
    fun `should allow literal boolean value for pause flag`() {
        assertThat {
            createAuroraConfigFieldHandlers(
                """{ 
                "groupId": "no.skatteetaten.aurora",
                    "version" : "1",
                    "pause" : true
                    
               }"""
            )
        }.isSuccess()
    }

    @Test
    fun `should allow string literal true value for pause flag`() {
        assertThat {
            createAuroraConfigFieldHandlers(
                """{ 
                "groupId": "no.skatteetaten.aurora",
                    "version" : "1",
                    "pause" : "true"
                    
               }"""
            )
        }.isSuccess()
    }

    @Test
    fun `should allow string literal value TRUE for puase flag`() {
        assertThat {
            createAuroraConfigFieldHandlers(
                """{ 
                "groupId": "no.skatteetaten.aurora",
                    "version" : "1",
                    "pause" : "TRUE"
                    
               }"""
            )
        }.isSuccess()
    }

    @Test
    fun `should not allow misspelled false for boolean flag`() {
        assertThat {
            createAuroraConfigFieldHandlers(
                """{ 
                "groupId": "no.skatteetaten.aurora",
                    "version" : "1",
                    "pause" : "fasle"
                    
               }"""
            )
        }.singleApplicationError("Not a valid boolean value.")
    }

    @Test
    fun `template type should not have default resource requirements`() {
        val spec = createAuroraDeploymentSpecForFeature(
            """{
           "type": "template"
        }"""
        )

        assertThat(spec).auroraDeploymentSpecMatches("template-spec-default.json")
    }

    @Test
    fun `deploy type should have default resource requirements`() {
        val spec = createAuroraDeploymentSpecForFeature(
            """{
                "groupId": "no.skatteetaten.aurora",
           "type": "deploy", 
           "version" : "1"
        }"""
        )

        assertThat(spec).auroraDeploymentSpecMatches("deploy-spec-default.json")
    }

    @Test
    fun `modify dc and ad for default parameters`() {

        val (dcResource, adResource) = modifyResources(
            """{ 
                "groupId": "no.skatteetaten.aurora",
                "version" : "1"
           }""",
            createEmptyDeploymentConfig(), createEmptyApplicationDeployment()
        )

        assertThat(adResource).auroraResourceModifiedByThisFeatureWithComment("Added information from deployment")
        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added labels, annotations")

        val ad = adResource.resource as ApplicationDeployment
        assertThat(ad.spec.deployTag).isEqualTo("1")
        assertThat(ad.spec.managementPath).isEqualTo(":8081/actuator")
        assertThat(ad.spec.splunkIndex).isNull()
        assertThat(ad.spec.releaseTo).isNull()

        assertThat(dcResource).auroraResourceMatchesFile("default-dc.json")
    }

    @Test
    fun `modify dc and ad for changed parameters`() {

        val (dcResource, adResource) = generateResources(
            app = """{ 
                "groupId": "no.skatteetaten.aurora",
                "version" : "1",
                "releaseTo" : "test", 
                "splunkIndex" : "test",
                "debug" : true
                
           }""",
            resources = mutableSetOf(createEmptyDeploymentConfig(), createEmptyApplicationDeployment()),
            files = listOf(AuroraConfigFile("simple.json", """{ "pause" : true }""", override = true)),
            createdResources = 0
        )

        assertThat(adResource).auroraResourceModifiedByThisFeatureWithComment("Added information from deployment")
        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added labels, annotations")

        val ad = adResource.resource as ApplicationDeployment
        assertThat(ad.spec.deployTag).isEqualTo("1")
        assertThat(ad.spec.managementPath).isEqualTo(":8081/actuator")
        assertThat(ad.spec.splunkIndex).isEqualTo("test")
        assertThat(ad.spec.releaseTo).isEqualTo("test")

        assertThat(dcResource).auroraResourceMatchesFile("changed-dc.json")
    }

    @Test
    fun `modify dc and ad for template application should not add resources`() {

        val (dcResource, _) = generateResources(
            app = """{ 
                "type" : "template",
                "releaseTo" : "test", 
                "splunkIndex" : "test",
                "debug" : true,
                "version" : "1"
           }""",
            resources = mutableSetOf(createEmptyDeploymentConfig(), createEmptyApplicationDeployment()),
            createdResources = 0
        )

        assertThat(dcResource).auroraResourceMatchesFile("changed-dc-template.json")
    }

    @Test
    fun `modify dc add nodeSelectors`() {

        val (dcResource) = generateResources(
            app = """{
                "groupId": "no.skatteetaten.aurora",
                "version" : "1",
                "nodeSelector": {
                    "selector1": "test",
                    "selector2": "test"
                }
           }""",
            resources = mutableSetOf(createEmptyDeploymentConfig(), createEmptyApplicationDeployment()),
            files = listOf(AuroraConfigFile("simple.json", """{ "pause" : true }""", override = true)),
            createdResources = 0
        )

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added labels, annotations")

        assertThat(dcResource).auroraResourceMatchesFile("changed-dc-nodeselector.json")
    }

    @Test
    fun `nodeSelectors must use static value for affiliation (from headerSpec) even if another value is provided in AuroraConfig`() {
        val (dcResource) = generateResources(
            app = """{
                |    "groupId": "no.skatteetaten.aurora",
                |    "version": "1",
                |    "nodeSelector": {
                |        "affiliation": "thisValueWillNotBeUsed",
                |        "selector1": "test",
                |        "selector2": "test"
                |    }
                |}""".trimMargin(),
            resources = mutableSetOf(createEmptyDeploymentConfig(), createEmptyApplicationDeployment()),
            files = listOf(AuroraConfigFile("simple.json", """{ "pause": true }""", override = true)),
            createdResources = 0
        )

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added labels, annotations")

        assertThat(dcResource).auroraResourceMatchesFile("changed-dc-nodeselector.json")
        assertThat(dcResource.resource).isInstanceOf(DeploymentConfig::class)

        val nodeSelectorsFromDC = (dcResource.resource as DeploymentConfig).spec.template.spec.nodeSelector
        assertThat(nodeSelectorsFromDC).doesNotContain("affiliation", "thisValueWillNotBeUsed")
        assertThat(nodeSelectorsFromDC).contains("affiliation", "paas")
    }
}
