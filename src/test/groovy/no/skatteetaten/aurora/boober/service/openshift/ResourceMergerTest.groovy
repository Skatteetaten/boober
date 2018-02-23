package no.skatteetaten.aurora.boober.service.openshift

import static no.skatteetaten.aurora.boober.model.ApplicationId.aid

import org.springframework.beans.factory.annotation.Autowired

import com.fasterxml.jackson.databind.JsonNode

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AbstractAuroraDeploymentSpecSpringTest
import no.skatteetaten.aurora.boober.service.OpenShiftObjectGenerator
import spock.lang.Unroll

class ResourceMergerTest extends AbstractAuroraDeploymentSpecSpringTest {

  String ENVIRONMENT = "utv"

  def auroraConfigJson = [
      "about.json"           : DEFAULT_ABOUT,
      "utv/about.json"       : DEFAULT_UTV_ABOUT,
      "webleveranse.json"    : WEB_LEVERANSE,
      "utv/webleveranse.json": '''{
  "type" : "development",
  "version" : "dev-SNAPSHOT"
}'''
  ]

  @Autowired
  OpenShiftObjectGenerator objectGenerator

  @Autowired
  OpenShiftClient client

  def "Merge DeploymentConfig"() {

    given:
      JsonNode existing = loadJsonResource("dc-webleveranse.json")
      JsonNode newResource = withDeploySpec { objectGenerator.generateDeploymentConfig("deploy-id", it, null) }

    when:
      def merged = ResourceMergerKt.mergeWithExistingResource(newResource, existing)

    then: "Preserves the lastTriggeredImage"
      def lastTriggeredImagePath = "/spec/triggers/0/imageChangeParams/lastTriggeredImage"
      newResource.at(lastTriggeredImagePath).missingNode
      existing.at(lastTriggeredImagePath).textValue() != null
      merged.at(lastTriggeredImagePath).textValue() == existing.at(lastTriggeredImagePath).textValue()

    and: "Preserves the container image attributes"
      (0..1).forEach {
        def imagePath = "/spec/template/spec/containers/$it/image"
        assert newResource.at(imagePath).missingNode
        assert existing.at(imagePath).textValue() != null
        assert merged.at(imagePath).textValue() == existing.at(imagePath).textValue()
      }
  }

  def "Merge namespace"() {

    given:
      JsonNode existing = loadJsonResource("namespace-aos-utv.json")
      JsonNode newResource = withDeploySpec { objectGenerator.generateNamespace(it.environment) }

    when:
      def merged = ResourceMergerKt.mergeWithExistingResource(newResource, existing)

    then: "Preserves all existing annotations"
      existing.at("/metadata/annotations") == merged.at("/metadata/annotations")
  }

  @Unroll
  def "Should update #type"() {

    given:
      def oldResource = loadJsonResource("openshift-objects/${type}.json")
      def newResource = loadJsonResource("openshift-objects/$type-new.json")

    expect:
      JsonNode merged = ResourceMergerKt.mergeWithExistingResource(newResource, oldResource)
      fields.each { assert merged.at(it) == oldResource.at(it) }

    where:
      type               | fields
      "service"          | ["/metadata/resourceVersion", "/spec/clusterIP"]
      "deploymentconfig" | ["/metadata/resourceVersion", "/spec/template/spec/containers/0/image"]
      "buildconfig"      |
          ["/metadata/resourceVersion", "/spec/triggers/0/imageChange/lastTriggeredImageID", "/spec/triggers/1/imageChange/lastTriggeredImageID"]
      "configmap"        | ["/metadata/resourceVersion"]

  }

  private <T> T withDeploySpec(Closure<T> c) {
    AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, aid(ENVIRONMENT, "webleveranse"))
    c(deploymentSpec)
  }
}
