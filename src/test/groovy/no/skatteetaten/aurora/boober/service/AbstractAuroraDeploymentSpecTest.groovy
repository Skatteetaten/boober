package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.mapper.v1.AuroraDeploymentSpecBuilderKt
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import spock.lang.Specification

import static no.skatteetaten.aurora.boober.model.ApplicationId.aid


abstract class AbstractAuroraDeploymentSpecTest extends Specification {

    static final DEFAULT_ABOUT = '''{
  "schemaVersion": "v1",
  "permissions": {
    "admin": "APP_PaaS_utv"
  },
  "affiliation" : "aos"
}'''

    static final String DEFAULT_UTV_ABOUT = '''{
  "cluster": "utv"
}'''

    static final String AOS_SIMPLE_JSON = '''{
  "certificate": true,
  "groupId": "ske.aurora.openshift",
  "artifactId": "aos-simple",
  "name": "aos-simple",
  "version": "1.0.3",
  "route": true,
  "type": "deploy"
}'''

    static final String WEB_LEVERANSE = '''{
  "applicationPlatform" : "web",
  "name" : "webleveranse",
  "groupId" : "no.skatteetaten.aurora",
  "artifactId" : "openshift-referanse-react",
  "replicas" : 1,
  "deployStrategy" : {
    "type" : "rolling"
  },
  "route" : true,
  "management" : {
    "port" : "8081",
    "path" : ""
  }
}'''


    static defaultAuroraConfig() {
        [
                "about.json"         : DEFAULT_ABOUT,
                "utv/about.json"     : DEFAULT_UTV_ABOUT,
                "aos-simple.json"    : AOS_SIMPLE_JSON,
                "utv/aos-simple.json": '''{ }'''
        ]
    }

    static final DEFAULT_AID = aid("utv", "aos-simple")



    static AuroraDeploymentSpec createDeploymentSpec(Map<String, String> auroraConfigJson, ApplicationId applicationId) {

        AuroraConfig auroraConfig = createAuroraConfig(auroraConfigJson)
        AuroraDeploymentSpecBuilderKt.createAuroraDeploymentSpec(auroraConfig, applicationId, "", [], [:])
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
