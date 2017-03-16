package services

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import no.skatteetaten.aurora.boober.BooberConfigService
import spock.lang.Specification

class BooberServiceTest extends Specification {

  def aboutWithoutName = """
{
  "affiliation": "mfp",
  "cluster": "utv",
  "type": "deploy",
  "build": {
    "GROUP_ID": "no.skatt.aurora",
    "ARTIFACT_ID": "console",
    "VERSION": "3.2.1"
  }
 }
 """

  def aboutWithName = """
{
  "affiliation": "mfp",
  "cluster": "utv",
  "type": "deploy",
  "name": "console2",
  "build": {
    "GROUP_ID": "no.skatt.aurora",
    "ARTIFACT_ID": "console",
    "VERSION": "3.2.1"
  }
 }
 """
/*
  def "Should merge muliple json files"() {
    given:
      def mapper = new ObjectMapper()
      mapper.registerModule(new KotlinModule())

      def service = new BooberConfigService(mapper)
      def files = new BooberConfigFiles()

    when:

     // def result = service.createBooberResult([files.booberConfigFiles, aboutWithName, files.globalApp, files.environmentAbout])

    then:
      //result.sources.size() == 4
      //result.config != null
  }
  */

}
