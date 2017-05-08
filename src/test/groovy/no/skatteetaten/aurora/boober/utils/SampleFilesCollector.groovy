package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.Configuration

class SampleFilesCollector {

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "verify-ebs-users"

  static Map<String, JsonNode> getQaEbsUsersSampleFiles() {
    return collectFilesToMapOfJsonNode("about.json", "${APP_NAME}.json", "${ENV_NAME}/about.json", "${ENV_NAME}/${APP_NAME}.json")
  }

  static Map<String, JsonNode> getQaEbsUsersSampleFilesForEnv(String envName) {
    return collectFilesToMapOfJsonNode("about.json", "${APP_NAME}.json", "${envName}/about.json", "${envName}/${APP_NAME}.json")
  }

  static Map<String, JsonNode> collectFilesToMapOfJsonNode(String... fileNames) {
    File configDir = new File(SampleFilesCollector.getResource("/samples/config").path)

    return fileNames.collectEntries { [(it), collectFile(configDir, it)]}
  }

  static JsonNode collectFile(File dirName, String name) {
    ObjectMapper mapper = new Configuration().mapper()

    def json = mapper.readValue(new File(dirName, name), JsonNode.class)

    return json

  }

}
