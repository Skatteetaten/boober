package services

import no.skatteetaten.aurora.boober.services.ConfigurationFileMergerKt
import spock.lang.Specification

class ConfigurationFileMergerTest extends Specification {

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

  def "Should merge muliple json files"() {
    when:
      def files = new AocConfigFiles()
      ConfigurationFileMergerKt.mergeJsonFiles([aboutWithName, files.globalApp, files.environmentAbout])

    then:
      true
  }

}
