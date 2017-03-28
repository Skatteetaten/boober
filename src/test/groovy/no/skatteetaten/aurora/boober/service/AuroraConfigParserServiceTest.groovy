package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.utils.SampleFilesCollector
import spock.lang.Specification

class AuroraConfigParserServiceTest extends Specification {

  def A() {

    given:
      def aocConfigParserService = new AuroraConfigParserService(new ValidationService())
      def aocConfig = new AuroraConfig(SampleFilesCollector.utvReferanseSampleFiles)

    when:
      def config = aocConfigParserService.createAuroraDcFromAuroraConfig(aocConfig, "utv", "referanse")
      println config

    then:
      true
  }
}
