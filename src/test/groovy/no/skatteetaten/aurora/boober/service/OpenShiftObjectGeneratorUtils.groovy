package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import spock.mock.DetachedMockFactory

class OpenShiftObjectGeneratorUtils {

  private static DetachedMockFactory factory = new DetachedMockFactory()

  static OpenShiftObjectGenerator createObjectGenerator() {

    def ve = new Configuration().velocity()
    def objectMapper = new Configuration().mapper()
    def userDetailsProvider = factory.Mock(UserDetailsProvider)
    userDetailsProvider.getAuthenticatedUser() >> new User("aurora", "token", "Aurora OpenShift")
    new OpenShiftObjectGenerator(
        userDetailsProvider, new VelocityTemplateJsonService(ve, objectMapper), objectMapper,
        factory.Mock(OpenShiftTemplateProcessor), factory.Mock(OpenShiftResourceClient))
  }
}
