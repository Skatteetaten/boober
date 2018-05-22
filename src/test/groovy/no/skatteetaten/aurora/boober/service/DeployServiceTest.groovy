package no.skatteetaten.aurora.boober.service

import java.time.Duration

import org.springframework.beans.factory.annotation.Autowired

import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import spock.lang.Unroll

class DeployServiceTest extends AbstractMockedOpenShiftSpecification {

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  DeployService deployService

  @Autowired
  RedeployService redeployService

  @Autowired
  GitService gitService

  @Autowired
  ObjectMapper mapper

  @Autowired
  AuroraConfigService auroraConfigService

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "aos-simple"
  def affiliation = "aos"

  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  def setup() {
    openShiftClient.projectExists(_) >> {
      false
    }

    openShiftClient.performOpenShiftCommand(_, _) >> {
      def cmd = it[1]
      def namespace = it[0]

      def name = cmd.payload.at("/metadata/name").textValue()
      def kind = cmd.payload.at("/kind").textValue().toLowerCase()
      try {
        def fileName = "$namespace-${name}-${kind}.json"
        def resource = loadResource(fileName)
        new OpenShiftResponse(cmd, mapper.readTree(resource))
      } catch (Exception ignored) {
        new OpenShiftResponse(cmd, cmd.payload)
      }
    }

    openShiftClient.getByLabelSelectors(_, _, _) >> []
    redeployService.triggerRedeploy(_, _) >> new RedeployService.RedeployResult()
  }

  def "Should prepare deploy environment for new project with ttl"() {
    given:
      def ads = auroraConfigService.
          createValidatedAuroraDeploymentSpecs(affiliation, [new ApplicationId(ENV_NAME, APP_NAME)])

    when:
      def deployResults = deployService.prepareDeployEnvironments(ads)


    then:
      deployResults.size() == 1
      def env = deployResults.keySet().first()
      env.ttl == Duration.ofDays(1)

      def deployResult = deployResults.values().first()
      def namespace = deployResult.openShiftResponses.find { it.command.payload.at("/kind").textValue() == "Namespace" }
      namespace != null
      namespace.command.payload.at("/metadata/labels/removeAfter").textValue() == "86400"
  }

  @Unroll
  def "Deploy from spec for #env/#name"() {
    given:
      def ads = auroraConfigService.
          createValidatedAuroraDeploymentSpecs(affiliation, [new ApplicationId(env, name)])
    when:
      def result = deployService.deployFromSpec(ads[0], true, true)

    then:
      result.success
      result.auroraDeploymentSpec
      result.deployId
      result.openShiftResponses.size() > 0

    where:
      env           | name
      'booberdev'   | 'reference'
      'booberdev'   | 'console'
      'webseal'     | 'sprocket'
      'booberdev'   | 'sprocket'
      'booberdev'   | 'reference-web'
      'booberdev'   | 'aos-simple'
      'secrettest'  | 'aos-simple'
      'release'     | 'aos-simple'
      'mounts'      | 'aos-simple'
      'secretmount' | 'aos-simple'
  }

}
