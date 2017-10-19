package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.ObjectMapper

import groovy.json.JsonOutput
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec

class AuroraDeploymentSpecRendererTest extends AbstractAuroraDeploymentSpecTest {

  ObjectMapper mapper = new ObjectMapper()

    def auroraConfigJson = [
                "about.json"         : DEFAULT_ABOUT,
                "utv/about.json"     : DEFAULT_UTV_ABOUT,
                "webleveranse.json"    : WEB_LEVERANSE,
                "utv/webleveranse.json": '''{ "type": "development", "version": "1" }'''
        ]

    def "Fails when admin groups is empty"() {
      given:
        AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, ApplicationId.aid("utv", "webleveranse"))
        def spec = AuroraDeploymentSpecRendererKt.renderJsonFromAuroraDeploymentSpec(deploymentSpec)

      when:
        def json = mapper.writeValueAsString(spec)

      then:
        println JsonOutput.prettyPrint(json)
    }
}
