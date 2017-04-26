package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

import no.skatteetaten.aurora.boober.Configuration
import spock.lang.Specification

@SpringBootTest(classes = [
    Configuration,
    EncryptionConfig,
    EncryptionService
])
class EncryptionServiceTest extends Specification {

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
