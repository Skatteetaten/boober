package no.skatteetaten.aurora.boober.service

import com.google.common.collect.ArrayTable
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.Permission
import no.skatteetaten.aurora.boober.model.Permissions
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.openshift.ImageStreamInformation
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftStatus
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.VerificationResult
import org.springframework.beans.factory.annotation.Autowired
import spock.mock.DetachedMockFactory

import static no.skatteetaten.aurora.boober.model.TemplateType.build
import static no.skatteetaten.aurora.boober.service.openshift.OperationType.CREATE

@DefaultOverride(auroraConfig = false)
class RedeployServiceTriggerRedeployTest extends AbstractMockedOpenShiftSpecification {

    @Autowired
    OpenShiftClient openShiftClient

    @Autowired
    RedeployService redeployService

    OpenShiftStatus openShiftStatus

    def setup() {
        openShiftClient.createOpenShiftCommand(_, _, _) >> { new OpenshiftCommand(CREATE, it[1]) }
        openShiftClient.performOpenShiftCommand(_, _) >> {
            OpenshiftCommand cmd = it[1]
            new OpenShiftResponse(cmd, cmd.payload)
        }
    }

    def createDeploymentSpec() {
        Permissions permissions = new Permissions(new Permission(null, null), null)
        AuroraDeployEnvironment env = new AuroraDeployEnvironment("paas", "utv", permissions)
        return new AuroraDeploymentSpec("v1", TemplateType.deploy, "name", new HashMap<String, Map<String, Object>>(), "dev", env, null, null, null, null, null, null)
    }

    def "Trigger redeploy service"() {
        setup:
            openShiftStatus = Mock(OpenShiftStatus.class)
            openShiftStatus.didImportImage() >> false
            openShiftStatus.hasResponse(_) >> false
            openShiftStatus.verifyImageStreamImport() >> { new VerificationResult(true) }
            openShiftStatus.findImageStreamInformation() >> { new ImageStreamInformation("img", "dock")}

        when:
            def result = redeployService.triggerRedeploy(createDeploymentSpec(), openShiftStatus)

        then:
            result == null
    }

}
