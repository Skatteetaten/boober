package no.skatteetaten.aurora.boober.service.openshift

import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.ClientType
import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.API_USER
import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.SERVICE_ACCOUNT

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.service.OpenShiftCommandBuilder
import no.skatteetaten.aurora.boober.service.OpenShiftObjectGenerator
import no.skatteetaten.aurora.boober.service.OpenShiftObjectLabelService
import no.skatteetaten.aurora.boober.service.OpenShiftTemplateProcessor
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.VelocityTemplateJsonService
import no.skatteetaten.aurora.boober.service.internal.SharedSecretReader
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    OpenShiftClient,
    OpenShiftObjectGenerator,
    OpenShiftTemplateProcessor,
    OpenShiftCommandBuilder,
    VelocityTemplateJsonService,
    SharedSecretReader,
    Config,
    UserDetailsProvider,
    OpenShiftObjectLabelService
])
class OpenShiftClientApplyTest extends Specification {

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    @ClientType(API_USER)
    @Primary
    OpenShiftResourceClient resourceClient() {

      factory.Mock(OpenShiftResourceClient)
    }

    @Bean
    @ClientType(SERVICE_ACCOUNT)
    OpenShiftResourceClient resourceClientSA() {

      factory.Mock(OpenShiftResourceClient)
    }
  }

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  OpenShiftCommandBuilder commandBuilder

  @Autowired
  @ClientType(API_USER)
  OpenShiftResourceClient userClient

  @Autowired
  @ClientType(SERVICE_ACCOUNT)
  OpenShiftResourceClient serviceAccountClient

  @Autowired
  ObjectMapper mapper

}
