package no.skatteetaten.aurora.boober.service

import java.time.Duration

import org.springframework.beans.factory.annotation.Autowired

import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse

class DeployServiceTest extends AbstractMockedOpenShiftSpecification {

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  DeployService deployService

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

    /*
    def namespaceJson = mapper.
        convertValue(["kind": "namespace", "metadata": ["labels": ["affiliation": affiliation]]], JsonNode.class)

    openShiftClient.createOpenShiftCommand(_, _, _, _) >> { new OpenshiftCommand(OperationType.CREATE, it[1]) }
    openShiftClient.createUpdateRolebindingCommand(_, _) >> {
      new OpenshiftCommand(OperationType.UPDATE, it[0], null, it[0])
    }
    openShiftClient.createUpdateNamespaceCommand(_, _) >> {
      new OpenshiftCommand(OperationType.UPDATE, namespaceJson, null, namespaceJson)
    }
    */

    openShiftClient.performOpenShiftCommand(_, _) >> {
      def cmd = it[1]
      def namespace = it[0]

      def name = cmd.payload.at("/metadata/name").textValue()
      def kind = cmd.payload.at("/kind").textValue().toLowerCase()
      try {
        def fileName = "$namespace-${name}-${kind}.json"
        def resource = loadResource(fileName)
        new OpenShiftResponse(cmd, mapper.readTree(resource))
      } catch (Exception e) {
        new OpenShiftResponse(cmd, cmd.payload)
      }
    }
    //openShiftClient.createOpenShiftDeleteCommands(_, _, _, _) >> []

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
      env.envTTL == Duration.ofDays(1)

      def deployResult = deployResults.values().first()
      def namespace = deployResult.openShiftResponses.find { it.command.payload.at("/kind").textValue() == "Namespace" }
      namespace != null
      namespace.command.payload.at("/metadata/labels/removeAfter").textValue() == "1970-01-02T00:00:00Z"
  }

}
