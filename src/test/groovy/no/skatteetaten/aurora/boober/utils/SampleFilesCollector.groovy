package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.ObjectMapper

import groovy.json.JsonSlurper
import no.skatteetaten.aurora.boober.Configuration

class SampleFilesCollector {

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "verify-ebs-users"

  static Map<String, Map<String, Object>> getQaEbsUsersSampleFiles() {
    return collectFilesToMapOfJsonNode("about.json", "${APP_NAME}.json", "${ENV_NAME}/about.json", "${ENV_NAME}/${APP_NAME}.json")
  }

  static Map<String, Map<String, Object>> collectFilesToMapOfJsonNode(String... fileNames) {
    File configDir = new File(SampleFilesCollector.getResource("/samples/config").path)

    return fileNames.collectEntries { [(it), collectFile(configDir, it)]}
  }

  static Map<String, Object> collectFile(File dirName, String name) {
    ObjectMapper mapper = new Configuration().mapper()

    def file = mapper.readTree(new File(dirName, name))

    return mapper.treeToValue(file, Map.class)
  }

  static Map<String, Object> jsonToMap(String json) {
    return new JsonSlurper().parseText(json) as Map<String, Object>
  }
}
