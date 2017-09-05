package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.controller.internal.DeployParams
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.facade.VaultFacade
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraSecretVault
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType

class DeployServiceFromGitTest extends AbstractMockedOpenShiftSpecification {

    @Autowired
    VaultFacade vaultFacade

    @Autowired
    OpenShiftClient openShiftClient

    @Autowired
    UserDetailsProvider userDetailsProvider

    @Autowired
    DeployService deployService

    @Autowired
    GitService gitService

    @Autowired
    DeployBundleService deployBundleService

    @Autowired
    DockerService dockerService

    @Autowired
    ObjectMapper mapper

    public static final String ENV_NAME = "booberdev"
    public static final String APP_NAME = "aos-simple"
    def affiliation = "aos"

    final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

    def setup() {
        userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
        openShiftClient.isValidUser(_) >> true
        openShiftClient.isValidGroup(_) >> true
        openShiftClient.prepare(_, _) >> { new OpenshiftCommand(OperationType.CREATE, it[1]) }
        openShiftClient.performOpenShiftCommand(_, _) >> {
            def cmd = it[0]
            new OpenShiftResponse(cmd, cmd.payload)
        }
        openShiftClient.createOpenshiftDeleteCommands(_, _, _, _) >> []
        openShiftClient.hasUserAccess(_, _) >> true

    }

    private void createRepoAndSaveFiles(String affiliation, AuroraConfig auroraConfig) {
        GitServiceHelperKt.createInitRepo(affiliation)
      deployBundleService.saveAuroraConfig(auroraConfig, false)
    }

    def "Should perform release and mark it"() {
        given:
        def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid, "aos")
        createRepoAndSaveFiles("aos", auroraConfig)

        when:

          deployService.executeDeploy(affiliation, new DeployParams([ENV_NAME], [APP_NAME], [], true))

        then:
        def git = gitService.checkoutRepoForAffiliation(affiliation)

        def history = gitService.tagHistory(git)
        history.size() == 1
        def revTag = history[0]

        revTag.taggerIdent != null
        revTag.fullMessage.startsWith("""{"deployId":""")
        revTag.tagName.startsWith("DEPLOY/aos-booberdev.aos-simple/")
        gitService.closeRepository(git)

    }

    def "Should perform two releases and get deploy history"() {
        given:
        GitServiceHelperKt.createInitRepo(affiliation)
        def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid, affiliation)
        def aid2 = new ApplicationId(ENV_NAME, "sprocket")
        def auroraConfig2 = AuroraConfigHelperKt.createAuroraConfig(aid2, affiliation)
        def mergedConfig = auroraConfig.
                copy(auroraConfig.auroraConfigFiles + auroraConfig2.auroraConfigFiles, affiliation)
        createRepoAndSaveFiles(affiliation, mergedConfig)

        when:
          deployService.executeDeploy(affiliation, new DeployParams([ENV_NAME], [APP_NAME, "sprocket"], [], true))

        then:
        def tags = deployService.deployHistory(affiliation)
        tags.size() == 2
        def revTag = tags[0]

        revTag.ident != null
        revTag.result.get("deployId") != null


        def revTag2 = tags[1]

        revTag2.ident != null
        revTag2.result.get("deployId") != null

    }

    def "Should perform release with secret and not include it in git tag"() {
        given:

        GitServiceHelperKt.createInitRepo(affiliation)
        vaultFacade.save(affiliation, new AuroraSecretVault("foo", ["latest.properties": "1.2.3"]), false)

        def auroraConfig = AuroraConfigHelperKt.
                createAuroraConfig(new ApplicationId("secrettest", "aos-simple"), affiliation)
          deployBundleService.saveAuroraConfig(auroraConfig, false)

        when:
          deployService.executeDeploy(affiliation, new DeployParams(["secrettest"], ["aos-simple"], [], true))

        then:
        def tags = deployService.deployHistory(affiliation)
        tags.size() == 1
        def revTag = tags[0]
        def resp = revTag.result["openShiftResponses"]

        resp.size() == 12

    }

    def "Should perform release and tag in docker repo"() {
        given:
        GitServiceHelperKt.createInitRepo(affiliation)
        def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(new ApplicationId("release", "aos-simple"), "aos")
        createRepoAndSaveFiles("aos", auroraConfig)

        when:

        1 * dockerService.tag(_) >>
                new ResponseEntity<JsonNode>(mapper.convertValue(["foo": "foo"], JsonNode.class), HttpStatus.OK)
          def result = deployService.executeDeploy(affiliation, new DeployParams(["release"], ["aos-simple"], [], true))
        then:
        result.size() == 1
        result[0].tagCommandResponse.statusCode.is2xxSuccessful()

    }

}
