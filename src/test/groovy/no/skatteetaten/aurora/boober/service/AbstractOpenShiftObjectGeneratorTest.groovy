package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.model.AbstractAuroraDeploymentSpecTest
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient

class AbstractOpenShiftObjectGeneratorTest extends AbstractAuroraDeploymentSpecTest {

  public static final String DEPLOY_ID = 'deploy-id'

  OpenShiftObjectGenerator createObjectGenerator() {

    def ve = new Configuration().velocity()
    def objectMapper = new Configuration().mapper()
    def userDetailsProvider = Mock(UserDetailsProvider)
    userDetailsProvider.getAuthenticatedUser() >> new User("aurora", "token", "Aurora OpenShift", [])
    new OpenShiftObjectGenerator(
        "docker-registry.aurora.sits.no:5000",
        new OpenShiftObjectLabelService(userDetailsProvider), new VelocityTemplateJsonService(ve, objectMapper), objectMapper,
        Mock(OpenShiftTemplateProcessor), Mock(OpenShiftResourceClient))
  }
}
