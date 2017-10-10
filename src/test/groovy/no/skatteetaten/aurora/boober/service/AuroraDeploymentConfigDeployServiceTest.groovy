package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraPermissions
import no.skatteetaten.aurora.boober.model.AuroraVolume
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import spock.lang.Ignore

class AuroraDeploymentConfigDeployServiceTest extends AbstractMockedOpenShiftSpecification {

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "aos-simple"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  @Autowired
  ObjectMapper mapper

  @Autowired
  DeployService service

  def "Should filter out ApplicationId if user has not access to secretVault"() {

    given:
      def volume = new AuroraVolume(null, null, null, new AuroraPermissions(null, ["yoda"]))
    when:
      def res = service.hasAccessToAllVolumes(volume)
    then:
      !res
  }

  def "Should filter out ApplicationId if user has not access to one or more mounts"() {

    given:
      def volume = new AuroraVolume(null, null,
          [new Mount("/foo", MountType.Secret, "foo", "foo", false, null, new AuroraPermissions(null, ["yoda"]))], null)
    when:
      def res = service.hasAccessToAllVolumes(volume)
    then:
      !res
  }

  def "Should fail due to missing config file"() {

    given:
      Map<String, JsonNode> files = AuroraConfigHelperKt.getSampleFiles(aid)
      files.remove("${APP_NAME}.json" as String)
      def auroraConfig =
          new AuroraConfig(files.collect { new AuroraConfigFile(it.key, it.value, false, null) }, "aos")

    when:
      auroraConfig.getFilesForApplication(aid)

    then:
      thrown(IllegalArgumentException)
  }

  @Ignore("Need to reimplement dryRun")
  def "Should get error if we want secrets but there are none "() {

    when:

      def json = mapper.convertValue(["secretVault": "notfound)"], JsonNode.class)
      def overrideAosFile = new AuroraConfigFile("aos-simple.json", json, true, null)
      service.dryRun("aos", new DeployParams([aid.environment], [aid.application], [overrideAosFile], false))

    then:
      thrown(AuroraConfigException)
  }

}

