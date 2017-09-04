package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import groovy.json.JsonOutput
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [no.skatteetaten.aurora.boober.Configuration,
        EncryptionService,
        AuroraConfigService,
        OpenShiftTemplateProcessor,
        GitService,
        OpenShiftObjectGenerator,
        Config,
        ObjectMapper])
class OpenShiftObjectGeneratorTest extends Specification {

    @Configuration
    static class Config {
        private DetachedMockFactory factory = new DetachedMockFactory()

        @Bean
        UserDetailsProvider userDetailsProvider() {
            factory.Mock(UserDetailsProvider)
        }

        @Bean
        GitService gitService() {
            factory.Mock(GitService)
        }

        @Bean
        OpenShiftClient openshiftClient() {
            factory.Mock(OpenShiftClient)
        }

        @Bean
        OpenShiftResourceClient client() {
            factory.Mock(OpenShiftResourceClient)
        }
    }

    @Autowired
    OpenShiftObjectGenerator openShiftService

    @Autowired
    UserDetailsProvider userDetailsProvider

    @Autowired
    AuroraConfigService auroraDeploymentConfigService

    @Autowired
    OpenShiftClient openShiftClient

    @Autowired
    OpenShiftResourceClient openShiftResourceClient

    @Autowired
    ObjectMapper mapper

    def setup() {
        userDetailsProvider.authenticatedUser >> new User("hero", "token", "Test User")
        openShiftClient.isValidGroup(_) >> true
        openShiftClient.isValidUser(_) >> true
    }

    def deployId = "123"

    @Shared
    def file = new ObjectMapper().convertValue([managementPath: ":8080/test"], JsonNode.class)

    @Shared
    def booberDevAosSimpleOverrides = [new AuroraConfigFile("booberdev/aos-simple.json", file, true, null)]

    @Unroll
    def "should create openshift objects for #env/#name"() {

        given:

        def vaults = ["foo": new AuroraSecretVault("foo", ["latest.properties": "Rk9PPWJhcgpCQVI9YmF6Cg=="], null,
                ["foo": null])]

        ApplicationId aid = new ApplicationId(env, name)
        DeployCommand deployCommand = new DeployCommand(aid, overrides)

        Map<String, JsonNode> files = AuroraConfigHelperKt.getSampleFiles(aid)

        if (templateFile != null) {
            files = AuroraConfigHelperKt.getSampleFiles(aid, "templates/$templateFile")

            def templateFileName = "/samples/processedtemplate/${aid.environment}/${aid.application}/$templateFile"
            def templateResult = this.getClass().getResource(templateFileName)
            JsonNode jsonResult = mapper.readTree(templateResult)

            openShiftResourceClient.post("processedtemplate", null, _, _) >>
                    new ResponseEntity<JsonNode>(jsonResult, HttpStatus.OK)
        }

        expect:
        def auroraConfig = new AuroraConfig(files.collect {
            new AuroraConfigFile(it.key, it.value, false, null)
        }, "aos")
        def aac = auroraDeploymentConfigService.createAuroraApplication(deployCommand, auroraConfig, vaults)

        List<JsonNode> generatedObjects = openShiftService.generateObjects(aac, deployId)

        def resultFiles = AuroraConfigHelperKt.getResultFiles(aid)

        def keys = resultFiles.keySet()

        generatedObjects.forEach {
            def key = getKey(it)
            assert keys.contains(key)
            compareJson(resultFiles[key], it)
        }

        generatedObjects.collect { getKey(it) } as Set == resultFiles.keySet()

        when:

        where:
        env           | name         | templateFile      | overrides
        "jenkins"     | "build"      | null              | []
        "booberdev"   | "build"      | null              | []
        "booberdev"   | "console"    | null              | []
        "booberdev"   | "aos-simple" | null              | booberDevAosSimpleOverrides
        "booberdev"   | "tvinn"      | "atomhopper.json" | []
        "secrettest"  | "aos-simple" | null              | []
        "booberdev"   | "sprocket"   | null              | []
        "release"     | "aos-simple" | null              | []
        "release"     | "build"      | null              | []
        "mounts"      | "aos-simple" | null              | []
        "secretmount" | "aos-simple" | null              | []


    }

    def compareJson(JsonNode jsonNode, JsonNode target) {
        assert JsonOutput.prettyPrint(target.toString()) == JsonOutput.prettyPrint(jsonNode.toString())
        true
    }

    def getKey(JsonNode it) {
        def kind = it.get("kind").asText().toLowerCase()
        def name = it.get("metadata").get("name").asText().toLowerCase()

        return "$kind/$name" as String
    }

}
