package no.skatteetaten.aurora.boober.facade

import no.skatteetaten.aurora.boober.controller.internal.SetupParams
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.AuroraConfigHelperKt
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.EncryptionService
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.GitServiceHelperKt
import no.skatteetaten.aurora.boober.service.OpenShiftObjectGenerator
import no.skatteetaten.aurora.boober.service.OpenShiftTemplateProcessor
import no.skatteetaten.aurora.boober.service.SecretVaultService
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import spock.lang.Specification
import spock.mock.DetachedMockFactory


@SpringBootTest(classes = [
        no.skatteetaten.aurora.boober.Configuration,
        SetupFacade,
        AuroraConfigService,
        OpenShiftObjectGenerator,
        OpenShiftTemplateProcessor,
        GitService,
        SecretVaultService,
        EncryptionService,
        AuroraConfigFacade,
        Config
]
        , properties = [
                "boober.git.urlPattern=/tmp/boober-test/%s",
                "boober.git.checkoutPath=/tmp/boober",
                "boober.git.username=",
                "boober.git.password="
        ])
class SetupFacadeFromGitTest extends Specification {

    @Configuration
    static class Config {
        private DetachedMockFactory factory = new DetachedMockFactory()

        @Bean
        UserDetailsProvider userDetailsProvider() {

            factory.Mock(UserDetailsProvider)
        }

        @Bean
        OpenShiftClient openshiftClient() {
            factory.Mock(OpenShiftClient)
        }

        @Bean
        OpenShiftResourceClient resourceClient() {
            factory.Mock(OpenShiftResourceClient)
        }
    }

    @Autowired
    OpenShiftClient openShiftClient
    

    @Autowired
    UserDetailsProvider userDetailsProvider

    @Autowired
    SetupFacade setupFacade

    @Autowired
    GitService gitService

    @Autowired
    AuroraConfigFacade configFacade


    public static final String ENV_NAME = "booberdev"
    public static final String APP_NAME = "aos-simple"
    def affiliation = "aos"

    final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

    def setup() {
        userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
        openShiftClient.isValidUser(_) >> true
        openShiftClient.isValidGroup(_) >> true
        openShiftClient.prepareCommands(_, _) >> []
        openShiftClient.findOldObjectUrls(_, _, _, _) >> []

    }

    private void createRepoAndSaveFiles(String affiliation, AuroraConfig auroraConfig) {
        GitServiceHelperKt.createInitRepo(affiliation)
        configFacade.saveAuroraConfig(affiliation, auroraConfig, false)
    }

    def "Should perform release and mark it"() {
        given:
        GitServiceHelperKt.createInitRepo(affiliation)
        def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid, "aos")
        createRepoAndSaveFiles("aos", auroraConfig)

        when:

        setupFacade.executeSetup(affiliation, new SetupParams([ENV_NAME], [APP_NAME], []))


        then:
        def git = gitService.checkoutRepoForAffiliation(affiliation)

        def history=gitService.tagHistory(git)
        history.size() == 1
        def revTag = history[0]

        revTag.taggerIdent != null
        revTag.fullMessage.startsWith("""{"deployCommand":{"applicationId":{"environment":"booberdev","application":"aos-simple"}""")
        revTag.tagName.startsWith("DEPLOY/booberdev-aos-simple/")
        gitService.closeRepository(git)


    }

    def "Should perform two releases and get deoploy history"() {
        given:
        GitServiceHelperKt.createInitRepo(affiliation)
        def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid, affiliation)
        def aid2 = new ApplicationId(ENV_NAME, "sprocket")
        def auroraConfig2 = AuroraConfigHelperKt.createAuroraConfig(aid2, affiliation)
        def mergedConfig = auroraConfig.copy(auroraConfig.auroraConfigFiles + auroraConfig2.auroraConfigFiles, affiliation)
        createRepoAndSaveFiles(affiliation, mergedConfig)

        when:
        setupFacade.executeSetup(affiliation, new SetupParams([ENV_NAME], [APP_NAME, "sprocket"], []))

        then:
        def tags = setupFacade.deployHistory(affiliation)
        tags.size() == 2
        def revTag = tags[0]

        revTag.ident != null
        revTag.result.get("deployCommand") != null


        def revTag2 = tags[1]

        revTag2.ident != null
        revTag2.result.get("deployCommand") != null

    }



}
