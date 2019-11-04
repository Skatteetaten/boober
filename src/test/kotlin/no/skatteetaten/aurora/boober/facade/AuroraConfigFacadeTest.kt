package no.skatteetaten.aurora.boober.facade

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isNotNull
import assertk.assertions.messageContains
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.GitServices.Domain.AURORA_CONFIG
import no.skatteetaten.aurora.boober.service.GitServices.TargetDomain
import no.skatteetaten.aurora.boober.utils.AuroraConfigSamples.Companion.createAuroraConfig
import no.skatteetaten.aurora.boober.utils.AuroraConfigSamples.Companion.getAuroraConfigSamples
import no.skatteetaten.aurora.boober.utils.recreateFolder
import no.skatteetaten.aurora.boober.utils.recreateRepo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import java.io.File

private val logger = KotlinLogging.logger {}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class AuroraConfigFacadeTest : AbstractSpringBootTest() {

    @Value("\${integrations.aurora.config.git.repoPath}")
    lateinit var repoPath: String

    @Value("\${integrations.aurora.config.git.checkoutPath}")
    lateinit var checkoutPath: String

    @Autowired
    lateinit var facade: AuroraConfigFacade

    @Autowired
    lateinit var service: AuroraConfigService

    @Autowired
    @TargetDomain(AURORA_CONFIG)
    lateinit var gitService: GitService

    @BeforeEach
    fun beforeEach() {
        recreateRepo(File(repoPath, "${auroraConfigRef.name}.git"))
        recreateFolder(File(checkoutPath))
        service.save(getAuroraConfigSamples())
    }

    val auroraConfigRef = AuroraConfigRef("paas", "master", "123abb")
    val adr = ApplicationDeploymentRef("utv", "simple")

    @Test
    fun `get spec for applications deployment refs`() {

        val specList = facade.findAuroraDeploymentSpec(auroraConfigRef, listOf(adr))

        assertThat(specList.size).isEqualTo(1)
        val spec = specList.first()
        assertThat(spec).isNotNull()
        //TODO What to assert here?
    }

    @Test
    fun `get spec for environment utv`() {

        val specList = facade.findAuroraDeploymentSpecForEnvironment(auroraConfigRef, "utv")
        assertThat(specList.size).isEqualTo(5)
    }

    @Test
    fun `get spec for applications deployment with override`() {

        val spec: AuroraDeploymentSpec = facade.findAuroraDeploymentSpecSingle(
            auroraConfigRef, adr,
            listOf(AuroraConfigFile("utv/simple.json", override = true, contents = """{ "version" : "foo" }"""))
        )

        assertThat(spec.get<String>("version")).isEqualTo("foo")
    }

    @Test
    fun `get config files for application`() {

        val files = facade.findAuroraConfigFilesForApplicationDeployment(auroraConfigRef, adr)
        assertThat(files.size).isEqualTo(4)
    }

    @Test
    fun `get all config files`() {
        val files = facade.findAuroraConfigFiles(auroraConfigRef)
        assertThat(files.size).isEqualTo(14)
    }

    @Test
    fun `get all config filenames`() {
        val files = facade.findAuroraConfigFileNames(auroraConfigRef)
        assertThat(files.size).isEqualTo(14)
    }

    @Test
    fun `find auroraconfig file`() {
        val file = facade.findAuroraConfigFile(auroraConfigRef, "utv/simple.json")
        assertThat(file).isNotNull()
    }

    @Test
    fun `validate aurora config`() {

        openShiftMock {

            rule({ path?.endsWith("/groups") }) {
                mockJsonFromFile("groups.json")
            }

            rule({ path?.endsWith("/users") }) {
                mockJsonFromFile("users.json")
            }
        }

        val auroraConfig = createAuroraConfig(adr, "paas")

        val validated=facade.validateAuroraConfig(auroraConfig,
            resourceValidation = false,
            auroraConfigRef = auroraConfigRef )
        assertThat(validated.size).isEqualTo(1)

    }

    @Test
    fun `Should fail to update invalid json file`() {

        val fileToChange = "utv/simple.json"
        val theFileToChange = facade.findAuroraConfigFile(auroraConfigRef, fileToChange)

        assertThat {
            facade.updateAuroraConfigFile(
                auroraConfigRef,
                fileToChange,
                """foo {"version": "1.0.0"}""",
                theFileToChange.version
            )
        }.isFailure().messageContains("utv/simple.json is not valid")
    }

    @Test
    fun `Should update one file in AuroraConfig`() {

        openShiftMock {

            rule({ path?.endsWith("/groups") }) {
                mockJsonFromFile("groups.json")
            }

            rule({ path?.endsWith("/users") }) {
                mockJsonFromFile("users.json")
            }
        }

        val fileToChange = "utv/simple.json"
        val theFileToChange = facade.findAuroraConfigFile(auroraConfigRef, fileToChange)

        facade.updateAuroraConfigFile(
            auroraConfigRef,
            fileToChange,
            """{"version": "1.0.0"}""",
            theFileToChange.version
        )

        val git = gitService.checkoutRepository(auroraConfigRef.name, auroraConfigRef.refName)
        val gitLog = git.log().call().toList().first()
        git.close()
        assertThat(gitLog.authorIdent.name).isEqualTo("Jayne Cobb")
        assertThat(gitLog.fullMessage).isEqualTo("Added: 0, Modified: 1, Deleted: 0")
    }

    @Test
    fun `Should not update one file in AuroraConfig if version is wrong`() {

        val fileToChange = "utv/simple.json"

        assertThat {
            facade.updateAuroraConfigFile(
                auroraConfigRef,
                fileToChange,
                """{"version": "1.0.0"}""",
                "incorrect hash"
            )
        }.isNotNull().isFailure()
            .messageContains("The provided version of the current file (incorrect hash) in AuroraConfig paas is not correct")
    }
}
