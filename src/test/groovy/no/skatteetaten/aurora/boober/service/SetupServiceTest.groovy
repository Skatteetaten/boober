package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.LoggingUtilsKt.setLogLevels

import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import spock.lang.Specification

class SetupServiceTest extends Specification {

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "verify-ebs-users"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  def parser = new AuroraConfigParserService()

  def udp = Mock(UserDetailsProvider)
  def openshiftService = new OpenShiftService(udp)
  def client = Mock(OpenShiftClient)

  /*
  val auroraConfigParserService: AuroraConfigParserService,
        val openShiftService: OpenShiftService,
        val openShiftClient: OpenShiftClient
   */
  def setupService = SetupService()

  def setupSpec() {
    setLogLevels()
  }

  def "foo"() {
  }
}