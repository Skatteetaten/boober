package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.mapper.v1.AuroraDeploymentSpecBuilderKt
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import spock.lang.Specification


abstract class AbstractAuroraDeploymentSpecTest extends Specification {

    final DEFAULT_ABOUT = '''{
  "schemaVersion": "v1",
  "permissions": {
    "admin": {
      "groups": "APP_PaaS_utv"
    }
  },
  "affiliation" : "aos"
}'''

    final String DEFAULT_UTV_ABOUT = '''{
  "cluster": "utv"
}'''

    final String AOS_SIMPLE_JSON = '''{
  "certificate": true,
  "groupId": "ske.aurora.openshift",
  "artifactId": "aos-simple",
  "name": "aos-simple",
  "version": "1.0.3",
  "route": true,
  "type": "deploy"
}'''


    static AuroraDeploymentSpec createDeploymentSpec(Map<String, String> auroraConfigJson, ApplicationId applicationId) {

        AuroraConfig auroraConfig = createAuroraConfig(auroraConfigJson)
        AuroraDeploymentSpecBuilderKt.createAuroraDeploymentSpec(applicationId, auroraConfig, "", [], [:])
    }


    static AuroraConfig createAuroraConfig(Map<String, String> auroraConfigJson) {
        def objectMapper = new ObjectMapper()
        def auroraConfigFiles = auroraConfigJson.collect { name, contents ->
            new AuroraConfigFile(name, objectMapper.readValue(contents, JsonNode), false, null)
        }
        def auroraConfig = new AuroraConfig(auroraConfigFiles, "aos")
        auroraConfig
    }
}
