package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.MockServerRestTemplateCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftRequestHandler
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig
import no.skatteetaten.aurora.boober.service.openshift.ServiceAccountTokenProvider
import no.skatteetaten.aurora.boober.service.openshift.UserDetailsTokenProvider

@WithUserDetails("aurora")
@RestClientTest
@SpringBootTest(classes = [
    MockRestServiceServiceInitializer,
    SecurityMock,
    no.skatteetaten.aurora.boober.Configuration,
    OpenShiftRequestHandler,
    OpenShiftResourceClientConfig,
    UserDetailsProvider,
    UserDetailsTokenProvider,
    ServiceAccountTokenProvider,
    OpenShiftClient,
    OpenShiftObjectGenerator,
    OpenShiftTemplateProcessor
], webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AbstractAuroraDeploymentSpecSpringTest extends AbstractAuroraDeploymentSpecTest {

  @Component
  static class MockRestServiceServiceInitializer implements InitializingBean {
    @Autowired
    MockServerRestTemplateCustomizer customizer

    @Autowired
    RestTemplate restTemplate

    @Override
    void afterPropertiesSet() throws Exception {
      customizer.customize(restTemplate)
    }
  }

  @Configuration
  static class SecurityMock {

    def users = [new User("aurora", "some-token", "Aurora OpenShift Test User")]

    @Bean
    UserDetailsService userDetailsService() {
      new UserDetailsService() {
        @Override
        UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
          User user = users.find { it.username == username }
          user ?: { throw new UsernameNotFoundException(username) }()
        }
      }
    }
  }

  String loadResource(String resourceName) {
    def folder = this.getClass().simpleName
    loadResource(folder, resourceName)
  }

  String loadResource(folder, String resourceName) {
    this.getClass().getResource("${folder}/$resourceName").text
  }
}
