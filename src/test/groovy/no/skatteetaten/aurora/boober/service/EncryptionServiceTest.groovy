package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.service.internal.SharedSecretReader
import spock.lang.Specification

@SpringBootTest(classes = [
    Configuration,
    Config,
    EncryptionService,
    AuroraMetrics,
    SharedSecretReader
])
class EncryptionServiceTest extends Specification {
  @org.springframework.context.annotation.Configuration
  static class Config {

    @Bean
    MeterRegistry meterRegistry() {
      Metrics.globalRegistry
    }
  }
  @Autowired
  EncryptionService service

  def "test encrypt and decrypt"() {
    def message = "FOO=BAR"
    when:
      def encrypted = service.encrypt(message)

    then:
      def result = service.decrypt(encrypted)
      result == message

  }
}
