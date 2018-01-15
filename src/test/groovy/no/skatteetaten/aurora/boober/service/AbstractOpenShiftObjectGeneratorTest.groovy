package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.model.AbstractAuroraDeploymentSpecTest
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient

class AbstractOpenShiftObjectGeneratorTest extends AbstractAuroraDeploymentSpecTest {

  public static final String DEPLOY_ID = '123'

  def userDetailsProvider = Mock(UserDetailsProvider)

  def openShiftResourceClient = Mock(OpenShiftResourceClient)

  def mapper = new Configuration().mapper()

  OpenShiftObjectGenerator createObjectGenerator(String username = "aurora") {

    def ve = new Configuration().velocity()
    userDetailsProvider.getAuthenticatedUser() >> new User(username, "token", "Aurora OpenShift", [])
    def templateProcessor = new OpenShiftTemplateProcessor(userDetailsProvider, openShiftResourceClient, mapper)

    new OpenShiftObjectGenerator(
        "docker-registry.aurora.sits.no:5000",
        new OpenShiftObjectLabelService(userDetailsProvider), new VelocityTemplateJsonService(ve, mapper),
        mapper,
        templateProcessor, openShiftResourceClient)
  }
}
