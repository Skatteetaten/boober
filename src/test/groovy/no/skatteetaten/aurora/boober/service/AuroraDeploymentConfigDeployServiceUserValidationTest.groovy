package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired

import no.skatteetaten.aurora.boober.controller.internal.DeployParams
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient

class AuroraDeploymentConfigDeployServiceUserValidationTest extends AbstractMockedOpenShiftSpecification {

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "aos-simple"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  @Autowired
  UserDetailsProvider userDetailsProvider

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  DeployBundleService deployBundleService

  @Autowired
  DeployService service

  def setup() {
    userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
  }

  private void createRepoAndSaveFiles(String affiliation, AuroraConfig auroraConfig) {
    GitServiceHelperKt.createInitRepo(affiliation)
    deployBundleService.saveAuroraConfig(auroraConfig, false)
  }

  def "Should get error if user is not valid"() {

    given:

      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples
      createRepoAndSaveFiles("aos", auroraConfig)
      openShiftClient.isValidUser("foo") >> false
      openShiftClient.isValidGroup(_) >> true


    when:
      service.dryRun("aos", new DeployParams([aid.environment], [aid.application], [], false))

    then:
      def e = thrown(AuroraConfigException)
      e.errors.size() == 1
      e.errors[0].messages[0].message == "The following users are not valid=foo"
      e.errors[0].messages[0].field.source == "about.json"

  }

  def "Should get error if group is not valid"() {

    given:
      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples
      createRepoAndSaveFiles("aos", auroraConfig)

      openShiftClient.isValidUser(_) >> true
      openShiftClient.isValidGroup(_) >> false
    when:

      service.dryRun("aos", new DeployParams([aid.environment], [aid.application], [], false))

    then:
      AuroraConfigException e = thrown()
      e.errors.size() == 1
      e.errors[0].messages[0].message == "The following groups are not valid=APP_PaaS_drift, APP_PaaS_utv"
      e.errors[0].messages[0].field.source == "about.json"
  }

}

