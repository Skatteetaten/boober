package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

import no.skatteetaten.aurora.boober.Configuration
import spock.lang.Specification

@SpringBootTest(classes = [
    Configuration,
    FileService,
    EncryptionService
])
class FileServiceTest extends Specification {

  @Autowired
  FileService service

  def "should find all files from dir"() {

    given:
      def configDir = new File(FileServiceTest.getResource("/samples/config").path)

    when:
      def res = service.findFiles(configDir)

    then:
      res.keySet() == ["about.json",
                       "verify-ebs-users.json",
                       "secrettest/verify-ebs-users.json",
                       "secrettest/about.json",
                       "booberdev/verify-ebs-users.json",
                       "booberdev/about.json"] as Set
  }

  def "should find and decrypt all secret v1 files"() {

    given:
      def configDir = new File(FileServiceTest.getResource("/samples/config/").path)

    when:
      def res = service.findAndDecryptSecretV1(configDir)

    then:
      res.keySet() == ["latest.properties"] as Set
      Base64.getDecoder().decode(res["latest.properties"]) == "FOO=BAR".bytes
  }
}
