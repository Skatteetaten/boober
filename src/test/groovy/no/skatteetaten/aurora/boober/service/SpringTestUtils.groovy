package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.MockServerRestTemplateCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate

import no.skatteetaten.aurora.boober.ServiceTypes
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.controller.security.User

class SpringTestUtils {
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

  @Component
  static class AuroraMockRestServiceServiceInitializer implements InitializingBean {
    @Autowired
    MockServerRestTemplateCustomizer customizer

    @Autowired
    @TargetService(ServiceTypes.AURORA)
    RestTemplate restTemplate

    @Override
    void afterPropertiesSet() throws Exception {
      customizer.customize(restTemplate)
    }
  }

  @Configuration
  static class SecurityMock {

    def users = [new User("aurora", "some-token", "Aurora OpenShift Test User", [])]

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

}
