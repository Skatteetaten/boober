package no.skatteetaten.aurora.boober.service

import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureWebClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithUserDetails

import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.model.AbstractAuroraDeploymentSpecTest
import no.skatteetaten.aurora.boober.service.internal.SharedSecretReader
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftRestTemplateWrapper
import no.skatteetaten.aurora.boober.service.openshift.token.ServiceAccountTokenProvider
import no.skatteetaten.aurora.boober.service.openshift.token.UserDetailsTokenProvider

@WithUserDetails("aurora")
@AutoConfigureWebClient
@SpringBootTest(classes = [
    SpringTestUtils.MockRestServiceServiceInitializer,
    SpringTestUtils.SecurityMock,
    Configuration,
    SharedSecretReader,
    OpenShiftRestTemplateWrapper,
    OpenShiftResourceClientConfig,
    UserDetailsProvider,
    UserDetailsTokenProvider,
    ServiceAccountTokenProvider,
    OpenShiftClient,
    OpenShiftObjectGenerator,
    OpenShiftTemplateProcessor,
    OpenShiftObjectLabelService
], webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AbstractAuroraDeploymentSpecSpringTest extends AbstractAuroraDeploymentSpecTest {

}
