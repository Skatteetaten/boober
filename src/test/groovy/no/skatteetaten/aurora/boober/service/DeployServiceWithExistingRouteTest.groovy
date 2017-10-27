package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.facade.VaultFacade
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.service.internal.AuroraDeployResult
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.utils.JsonNodeUtilsKt
import org.springframework.beans.factory.annotation.Autowired

class DeployServiceWithExistingRouteTest extends AbstractMockedOpenShiftSpecification {

  @Autowired
  VaultFacade vaultFacade

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  DeployService deployService

  @Autowired
  GitService gitService

  @Autowired
  DockerService dockerService

  @Autowired
  ObjectMapper mapper

  public static final String ENV_NAME = "mounts"
  public static final String APP_NAME = "aos-simple"
  def affiliation = "aos"

  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  def setup() {

    def namespaceJson = mapper.
        convertValue(["kind": "namespace", "metadata": ["labels": ["affiliation": affiliation], "name": "foo"]], JsonNode.class)
    openShiftClient.createOpenShiftCommand(_, _) >> {

        JsonNodeHelperKt.modifyCommandIfRoute(it[1])
    }

    openShiftClient.createUpdateRolebindingCommand(_, _) >> {
      new OpenshiftCommand(OperationType.UPDATE, it[0], null, it[0])
    }
    openShiftClient.createUpdateNamespaceCommand(_, _) >> {
      new OpenshiftCommand(OperationType.UPDATE, namespaceJson, null, namespaceJson)
    }

    openShiftClient.performOpenShiftCommand(_, _) >> {
      def cmd = it[1]
      new OpenShiftResponse(cmd, cmd.payload)
    }
    openShiftClient.createOpenShiftDeleteCommands(_, _, _, _) >> []
  }

  def "Should delete and create route"() {
    when:
      List<AuroraDeployResult> deployResults = deployService.
              executeDeploy(affiliation, [new ApplicationId(ENV_NAME, APP_NAME)], [], true)

    then:
      def result = deployResults[0]
      def resultSentences = result.openShiftResponses.collect {
        def name = JsonNodeUtilsKt.getOpenshiftName(it.command.payload)
        def kind = JsonNodeUtilsKt.getOpenshiftKind(it.command.payload)
        "${it.command.operationType} $kind $name".trim()
      }
      resultSentences ==
          ['CREATE projectrequest aos-mounts',
           'UPDATE namespace foo',
           'CREATE rolebinding admin',
           'CREATE deploymentconfig aos-simple',
           'CREATE service aos-simple',
           'CREATE imagestream aos-simple',
           'CREATE buildconfig aos-simple',
           'CREATE configmap aos-simple',
           'DELETE route aos-simple',
           'CREATE route aos-simple',
           'DELETE route aos-simple-bar',
           'CREATE route aos-simple-bar',
          ]

  }

}
