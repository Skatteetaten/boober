package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

import no.skatteetaten.aurora.boober.Configuration
import spock.lang.Specification

@SpringBootTest(classes = [
    Configuration,
    EncryptionService
])
class EncryptionServiceTest extends Specification {

  @Autowired
  EncryptionService service

  def "test encrypt and decrypt"() {
    given:
      def file = File.createTempFile("booberencrypt", ".tmp")
      def message = Base64.getEncoder().encodeToString("FOO=BAR".bytes)
    when:
      service.encrypt(message, file)

    then:
      def result = service.decrypt(file)
      result == message
      file.deleteOnExit()

  }

  def "test decrypt"() {
    when:
      def file = new File(this.getClass().getResource("/samples/config/.secretv1/latest.properties").path)
      def result = service.decrypt(file)

    then:
      "FOO=BAR".bytes == Base64.getDecoder().decode(result)

  }
}
