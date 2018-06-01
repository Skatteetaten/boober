package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.web.client.RestTemplate

import no.skatteetaten.aurora.boober.ServiceTypes
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.controller.security.User

class SpringTestUtils {
  @Configuration
  static class MockRestServiceServiceInitializer {

    @Autowired
    RestTemplate restTemplate

    @Bean
    MockRestServiceServer mockRestServiceServer() {
      MockRestServiceServer.createServer(restTemplate)
    }
  }

  @Configuration
  static class SkapMockRestServiceServiceInitializer {

    @Autowired
    @TargetService(ServiceTypes.SKAP)
    RestTemplate restTemplate

    @Bean
    MockRestServiceServer mockRestServiceServer() {
      MockRestServiceServer.createServer(restTemplate)
    }
  }

  @Configuration
  static class AuroraMockRestServiceServiceInitializer {

    @Autowired
    @TargetService(ServiceTypes.AURORA)
    RestTemplate restTemplate

    @Bean
    MockRestServiceServer mockRestServiceServer() {
      MockRestServiceServer.createServer(restTemplate)
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
