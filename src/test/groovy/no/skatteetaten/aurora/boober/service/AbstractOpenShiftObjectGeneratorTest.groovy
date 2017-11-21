package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.AbstractAuroraDeploymentSpecTest
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient

class AbstractOpenShiftObjectGeneratorTest extends AbstractAuroraDeploymentSpecTest {

  OpenShiftObjectGenerator createObjectGenerator() {

    def ve = new Configuration().velocity()
    def objectMapper = new Configuration().mapper()
    def userDetailsProvider = Mock(UserDetailsProvider)
    userDetailsProvider.getAuthenticatedUser() >> new User("aurora", "token", "Aurora OpenShift")
    new OpenShiftObjectGenerator(
        userDetailsProvider, new VelocityTemplateJsonService(ve, objectMapper), objectMapper,
        Mock(OpenShiftTemplateProcessor), Mock(OpenShiftResourceClient))
  }
}
