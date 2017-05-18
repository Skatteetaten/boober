package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode

import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile

class AuroraConfigHelper {

  static AuroraConfig createAuroraConfig(ApplicationId aid, Map<String, String> secrets = [:]) {
    Map<String, JsonNode> files = SampleFilesCollector.getSampleFiles(aid)
    new AuroraConfig(files.collect { new AuroraConfigFile(it.key, it.value, false) }, secrets)
  }
}
