package no.skatteetaten.aurora.boober.service

import org.springframework.boot.test.autoconfigure.web.client.RestClientTest
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithUserDetails

import no.skatteetaten.aurora.boober.model.AbstractAuroraDeploymentSpecTest
import no.skatteetaten.aurora.boober.service.internal.SharedSecretReader
import no.skatteetaten.aurora.boober.service.openshift.token.LocalKubeConfigTokenProvider
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftRequestHandler
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig
import no.skatteetaten.aurora.boober.service.openshift.token.ServiceAccountTokenProvider
import no.skatteetaten.aurora.boober.service.openshift.token.UserDetailsTokenProvider

@WithUserDetails("aurora")
@RestClientTest
@SpringBootTest(classes = [
    SpringTestUtils.MockRestServiceServiceInitializer,
    SpringTestUtils.SecurityMock,
    no.skatteetaten.aurora.boober.Configuration,
    SharedSecretReader,
    OpenShiftRequestHandler,
    OpenShiftResourceClientConfig,
    UserDetailsProvider,
    UserDetailsTokenProvider,
    ServiceAccountTokenProvider,
    OpenShiftClient,
    OpenShiftObjectGenerator,
    OpenShiftTemplateProcessor,
    VelocityTemplateJsonService,
    OpenShiftObjectLabelService,
    LocalKubeConfigTokenProvider
], webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AbstractAuroraDeploymentSpecSpringTest extends AbstractAuroraDeploymentSpecTest {

}
