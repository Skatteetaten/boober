package no.skatteetaten.aurora.boober.facade

import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isNotNull
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.utils.AuroraConfigSamples.Companion.getAuroraConfigSamples
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

private val logger = KotlinLogging.logger {}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class AuroraConfigFacadeTest : AbstractSpringBootTest() {

    @Autowired
    lateinit var facade: AuroraConfigFacade

    @MockkBean
    lateinit var auroraConfigService: AuroraConfigService

    val auroraConfigRef = AuroraConfigRef("paas", "master", "123abb")
    val auroraConfig = getAuroraConfigSamples()

    @BeforeEach
    fun beforeEach() {
        every { auroraConfigService.findAuroraConfig(auroraConfigRef) } returns auroraConfig
        every { auroraConfigService.resolveToExactRef(auroraConfigRef) } returns auroraConfigRef
    }

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

    // TODO: fix this test
    /*
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

        val validated=facade.validateAuroraConfig(auroraConfig,
            resourceValidation = false,
            auroraConfigRef = auroraConfigRef )
        assertThat(validated.size).isEqualTo(5)

    }
     */

    @Test
    fun `Should fail to update invalid json file`() {

        val fileToChange = "utv/simple.json"
        val theFileToChange = auroraConfig.files.find { it.name == fileToChange }

        assertThat {
            facade.updateAuroraConfigFile(
                auroraConfigRef,
                fileToChange,
                """foo {"version": "1.0.0"}""",
                theFileToChange?.version
            )
        }.isFailure().hasMessage("asdf")
    }

    /* TODO FEATURE : move to facade
    @Test
    fun `Should update one file in AuroraConfig`() {

        every {
            auroraDeploymentContextService.createValidatedAuroraDeploymentContexts(
                any(),
                any()
            )
        } returns emptyList()
        val auroraConfig = createAuroraConfig(defaultAuroraConfig())
        auroraConfigService.save(auroraConfig)

        val fileToChange = "utv/simple.json"
        val theFileToChange = auroraConfig.files.find { it.name == fileToChange }

        auroraConfigService.updateAuroraConfigFile(
            ref,
            fileToChange,
            """{"version": "1.0.0"}""",
            theFileToChange?.version
        )

        val git = gitService.checkoutRepository(ref.name, ref.refName)
        val gitLog = git.log().call().toList().first()
        git.close()
        assertThat(gitLog.authorIdent.name).isEqualTo("Aurora Test User")
        assertThat(gitLog.fullMessage).isEqualTo("Added: 0, Modified: 1, Deleted: 0")
    }


    @Test
    fun `Should not update one file in AuroraConfig if version is wrong`() {

        val fileToChange = "${aid.environment}/${aid.application}.json"
        val auroraConfig = createAuroraConfig(defaultAuroraConfig())
        auroraConfigService.save(auroraConfig)
        var count = 0

        assertThat {
            auroraConfigService.updateAuroraConfigFile(ref, fileToChange, """{"version": "1.0.0"}""", "incorrect hash")
                as AuroraVersioningException
        }.isNotNull().isFailure()
            .message().all { count++ }
        assertThat(count).isEqualTo(1)
    }
     */
}
