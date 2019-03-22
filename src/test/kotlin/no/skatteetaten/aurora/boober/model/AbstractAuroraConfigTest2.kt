package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.module.kotlin.readValue
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef.Companion.aid
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecService
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import no.skatteetaten.aurora.boober.utils.jsonMapper

// TODO: Kan vi lese denne auroraConfigen fra noen filer? Vi har jo noen filer vi bruker i andre tester
abstract class AbstractAuroraConfigTest2 : ResourceLoader() {

    val AFFILIATION = "aos"

    val DEFAULT_ABOUT = """{
  "schemaVersion": "v1",
  "permissions": {
    "admin": "APP_PaaS_utv"
  },
  "segment" : "aurora",
  "affiliation" : "$AFFILIATION"
}"""

    val DEFAULT_UTV_ABOUT = """{
    "cluster": "utv"
}"""

    val AOS_SIMPLE_JSON = """{
"certificate": true,
"groupId": "ske.aurora.openshift",
"artifactId": "aos-simple",
"name": "aos-simple",
"version": "1.0.3",
"route": true,
"type": "deploy"
}"""

    val REFERENCE = """{
"groupId" : "no.skatteetaten.aurora",
"replicas" : 1,
"version" : "1",
"route" : true,
"type" : "deploy"
}"""

    val REFERENCE_WEBSEAL = """{
"groupId" : "no.skatteetaten.aurora",
"replicas" : 1,
"version" : "1",
"webseal":  true,
"route" : true,
"type" : "deploy"
}"""

    val REF_APP_JSON = """{
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
}"""

    val REF_APP_JSON_LONG_DB_NAME = """{
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
}"""
    val WEB_LEVERANSE = """{
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
}"""

    val ATOMHOPPER = """{
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
}"""

    fun defaultAuroraConfig(): MutableMap<String, String> = mutableMapOf(
        "about.json" to DEFAULT_ABOUT,
        "utv/about.json" to DEFAULT_UTV_ABOUT,
        "aos-simple.json" to AOS_SIMPLE_JSON,
        "utv/aos-simple.json" to """{ }"""
    )

    val DEFAULT_AID = aid("utv", "aos-simple")

    fun createAuroraConfig(auroraConfigJson: Map<String, String>): AuroraConfig {

        val auroraConfigFiles = auroraConfigJson.map { AuroraConfigFile(it.key, it.value) }
        return AuroraConfig(auroraConfigFiles, "aos", "master")
    }

    fun modify(
        auroraConfig: MutableMap<String, String>,
        fileName: String,
        fn: (MutableMap<String, Any>) -> Unit
    ): MutableMap<String, String> {
        val file = auroraConfig[fileName]!!
        val modified = modify(file, fn)

        auroraConfig[fileName] = modified
        return auroraConfig
    }

    fun modify(configFile: String, fn: (MutableMap<String, Any>) -> Unit): String {

        val content: MutableMap<String, Any> = jsonMapper().readValue(configFile)
        fn(content)
        return jsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(content)
    }

    fun createDeploymentSpec(
        auroraConfigJson: Map<String, String>,
        ref: ApplicationDeploymentRef
    ): AuroraDeploymentSpecInternal {
        return AuroraDeploymentSpecService.createAuroraDeploymentSpecInternal(createAuroraConfig(auroraConfigJson), ref)
    }
}
