package no.skatteetaten.aurora.boober.model

import static no.skatteetaten.aurora.boober.model.ApplicationId.aid

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import no.skatteetaten.aurora.boober.service.AbstractSpec


abstract class AbstractAuroraConfigTest extends AbstractSpec {


  static final AFFILIATION = "aos"

  static final DEFAULT_ABOUT = """{
  "schemaVersion": "v1",
  "permissions": {
    "admin": "APP_PaaS_utv"
  },
  "segment" : "aurora",
  "affiliation" : "$AFFILIATION"
}"""

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
  static final String REFERENCE = '''{
  "groupId" : "no.skatteetaten.aurora",
  "replicas" : 1,
  "version" : "1",
  "route" : true,
  "type" : "deploy"
}'''

  public static final String REF_APP_JSON = '''{
  "name" : "reference",
  "groupId" : "no.skatteetaten.aurora.openshift",
  "artifactId" : "openshift-reference-springboot-server",
  "version" : "1.0.8",
  "certificate" : true,
  "database" : {
    "REFerence" : "auto"
  },
  "type" : "deploy",
  "route" : true
}'''

  public static final String REF_APP_JSON_LONG_DB_NAME = '''{
  "name" : "reference",
  "groupId" : "no.skatteetaten.aurora.openshift",
  "artifactId" : "openshift-reference-springboot-server",
  "version" : "1.0.8",
  "certificate" : true,
  "database" : {
    "REFerence_name" : "auto"
  },
  "type" : "deploy",
  "route" : true
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

  static final ATOMHOPPER = '''{
  "name": "atomhopper",
  "type" : "template",
  "template" : "atomhopper",
  "parameters" : {
    "SPLUNK_INDEX" : "test",
    "APP_NAME" : "atomhopper",
    "FEED_NAME" : "tolldeklarasjon",
    "DOMAIN_NAME" : "localhost",
    "SCHEME" : "http",
    "DB_NAME" : "atomhopper",
    "AFFILIATION" : "aos"
  }
}'''

  static Map<String, String> defaultAuroraConfig() {
    [
        "about.json"         : DEFAULT_ABOUT,
        "utv/about.json"     : DEFAULT_UTV_ABOUT,
        "aos-simple.json"    : AOS_SIMPLE_JSON,
        "utv/aos-simple.json": '''{ }'''
    ]
  }

  static final DEFAULT_AID = aid("utv", "aos-simple")

  static AuroraConfig createAuroraConfig(Map<String, String> auroraConfigJson) {

    def auroraConfigFiles = auroraConfigJson.collect { name, contents ->
      new AuroraConfigFile(name, contents, false)
    }
    def auroraConfig = new AuroraConfig(auroraConfigFiles, "aos")
    auroraConfig
  }

  static Map<String, String> modify(Map<String, String> auroraConfig, String fileName, Closure modifier) {
    def configFile = auroraConfig[fileName]
    String modifiedJson = modify(configFile, modifier)
    auroraConfig[fileName] = modifiedJson
    return auroraConfig
  }

  static String modify(String configFile, Closure modifier) {
    def asJson = new JsonSlurper().parseText(configFile)
    modifier.resolveStrategy = Closure.DELEGATE_FIRST
    modifier.delegate = asJson
    modifier()
    JsonOutput.prettyPrint(JsonOutput.toJson(asJson))
  }
}
