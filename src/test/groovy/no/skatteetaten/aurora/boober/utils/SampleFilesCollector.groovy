package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.Configuration

class SampleFilesCollector {

  static Map<String, JsonNode> getUtvReferanseSampleFiles() {
    return collectFilesToMapOfJsonNode("about.json", "referanse.json", "utv/about.json", "utv/referanse.json")
  }

  static Map<String, JsonNode> collectFilesToMapOfJsonNode(String... fileNames) {
    ObjectMapper mapper = new Configuration().mapper()
    File configDir = new File(SampleFilesCollector.getResource("/samples/config").path)

    return fileNames.collectEntries { [(it), mapper.readTree(new File(configDir, it))] }
  }
}
