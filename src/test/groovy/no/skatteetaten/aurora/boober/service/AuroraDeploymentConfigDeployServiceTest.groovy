package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraConfigHelperKt
import no.skatteetaten.aurora.boober.model.AuroraVolume
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType

class AuroraDeploymentConfigDeployServiceTest extends AbstractMockedOpenShiftSpecification {

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "aos-simple"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  @Autowired
  ObjectMapper mapper

  @Autowired
  DeployService service

/*
  def "Should filter out ApplicationId if user is not in group that has has to secretVault"() {

    given:

      this.openShiftClient.isUserInGroup("hero", "APP_jedi_dev") >> false
      def volume = new AuroraVolume(null, null, null, new AuroraPermissions(["APP_jedi_dev"]))
    when:
      def res = service.hasAccessToAllVolumes(volume)
    then:
      !res
  }

  def "Should filter out ApplicationId if user has not access to one or more mounts"() {

    given:
      this.openShiftClient.isUserInGroup("hero", "APP_jedi_dev") >> false
      def volume = new AuroraVolume(null, null,
          [new Mount("/foo", MountType.Secret, "foo", "foo", false, null, new AuroraPermissions(["APP_jedi_dev"]))],
          null)
    when:
      def res = service.hasAccessToAllVolumes(volume)
    then:
      !res
  }
*/

  def "Should fail due to missing config file"() {

    given:
      Map<String, JsonNode> files = AuroraConfigHelperKt.getSampleFiles(aid)
      files.remove("${APP_NAME}.json" as String)
      def auroraConfig =
          new AuroraConfig(files.collect { new AuroraConfigFile(it.key, it.value, false) }, "aos")

    when:
      auroraConfig.getFilesForApplication(aid)

    then:
      thrown(IllegalArgumentException)
  }

  @DefaultOverride(auroraConfig = false)
  def "Should throw ValidationException due to missing required properties"() {

    given: "AuroraConfig without build properties"
      Map<String, JsonNode> files = AuroraConfigHelperKt.getSampleFiles(aid)
      (files.get("aos-simple.json") as ObjectNode).remove("version")
      (files.get("booberdev/aos-simple.json") as ObjectNode).remove("version")
      AuroraConfig auroraConfig =
          new AuroraConfig(files.collect { new AuroraConfigFile(it.key, it.value, false) }, "aos")
    when:

      createRepoAndSaveFiles(auroraConfig)

    then:
      def ex = thrown(MultiApplicationValidationException)
      ex.errors[0].throwable.message ==
          "Config for application aos-simple in environment booberdev contains errors. Version must be set as string, Version must be set."
  }
}

