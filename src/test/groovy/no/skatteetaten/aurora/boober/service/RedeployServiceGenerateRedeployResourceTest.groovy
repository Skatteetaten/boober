package no.skatteetaten.aurora.boober.service

import io.fabric8.kubernetes.api.model.ObjectReference
import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.DeploymentConfigSpec
import io.fabric8.openshift.api.model.DeploymentTriggerImageChangeParams
import io.fabric8.openshift.api.model.DeploymentTriggerPolicy
import io.fabric8.openshift.api.model.ImageStream
import io.fabric8.openshift.api.model.ImageStreamSpec
import io.fabric8.openshift.api.model.TagReference
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.utils.JsonNodeUtilsKt
import spock.lang.Ignore
import spock.lang.Specification

@Ignore("Not relevant any more?")
class RedeployServiceGenerateRedeployResourceTest extends Specification {

  def openShiftObjectGenerator = Mock(OpenShiftObjectGenerator)
  def redeployService = new RedeployService(Mock(OpenShiftClient), openShiftObjectGenerator)

  def "Should not create any resource given on empty values for ImageStream and DeploymentConfig"() {
    when:
      def result = redeployService.generateImageStreamTagResource(new ImageStream(), new DeploymentConfig())

    then:
      result == null
  }

  def "Should create ImageStreamTag when imageInformation and imageName is set"() {
    given:
      def imageStream = new ImageStream(
          spec: new ImageStreamSpec(tags: [new TagReference(from: new ObjectReference(name: 'imagestream-name'))]))

      def imageChangeParams = new DeploymentTriggerImageChangeParams(lastTriggeredImage: '',
          from: new ObjectReference(name: 'deploymentconfig-name:version'))
      def deploymentConfig = new DeploymentConfig(
          spec: new DeploymentConfigSpec(triggers: [new DeploymentTriggerPolicy(imageChangeParams: imageChangeParams)]))

    when:
      def result = redeployService.generateImageStreamTagResource(imageStream, deploymentConfig)

    then:
      JsonNodeUtilsKt.getOpenshiftKind(result) == 'imagestreamtag'
  }
}
