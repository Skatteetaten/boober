package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AocConfig
import no.skatteetaten.aurora.boober.model.Config
import no.skatteetaten.aurora.boober.utils.SampleFilesCollector
import spock.lang.Specification

class AocConfigParserServiceTest extends Specification {

  def A() {

    given:
      def validationService = Mock(ValidationService)
      def aocConfigParserService = new AocConfigParserService(validationService)
      def aocConfig = new AocConfig(SampleFilesCollector.utvReferanseSampleFiles)

    when:
      def config = aocConfigParserService.createConfigFromAocConfigFiles(aocConfig, "utv", "referanse")
      println config

    then:
      true
  }
}
