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

  def message = "FOO=BAR".bytes

  def "test encrypt and decrypt"() {
    when:
      def encrypted = service.encrypt(message)

    then:
      def result = service.decrypt(encrypted)
      result == message
  }

  def "decrypt version 1"() {
    given:
      def base64Message = message.encodeBase64().toString().bytes
      def encrypted = service.encrypt(base64Message).replace("Boober:2", "Boober:1")

    when:
      def decrypted = service.decrypt(encrypted)

    then:
      decrypted == message
  }
}
