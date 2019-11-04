package no.skatteetaten.aurora.boober.unit

import assertk.all
import assertk.assertThat
import assertk.assertions.containsAll
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.AuroraDeploymentContextService
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.AbstractAuroraConfigTest
import no.skatteetaten.aurora.boober.utils.AuroraConfigSamples.Companion.createAuroraConfig
import no.skatteetaten.aurora.boober.utils.recreateFolder
import no.skatteetaten.aurora.boober.utils.recreateRepo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

// TODO: Remove this test, everything is covered in Facade test.
class AuroraConfigServiceTest : AbstractAuroraConfigTest() {

    val REMOTE_REPO_FOLDER = File("build/gitrepos_auroraconfig_bare").absoluteFile.absolutePath
    val CHECKOUT_PATH = File("build/auroraconfigs").absoluteFile.absolutePath
    val AURORA_CONFIG_NAME = AFFILIATION
    val aid = DEFAULT_AID
    val ref = AuroraConfigRef(AURORA_CONFIG_NAME, "master", "123")

    val userDetailsProvider = mockk<UserDetailsProvider>()
    val auroraMetrics = AuroraMetrics(SimpleMeterRegistry())
    val gitService = GitService(
        userDetailsProvider,
        "$REMOTE_REPO_FOLDER/%s",
        CHECKOUT_PATH,
        "",
        "",
        auroraMetrics
    )
    val auroraDeploymentContextService: AuroraDeploymentContextService = mockk()
    val auroraConfigService = AuroraConfigService(
        gitService = gitService,
        bitbucketProjectService = mockk(),
        auroraDeploymentContextService = auroraDeploymentContextService,
        cluster = "qa",
        project = "ac"
    )

    @BeforeEach
    fun setup() {
        recreateRepo(File(REMOTE_REPO_FOLDER, "$AURORA_CONFIG_NAME.git"))
        recreateFolder(File(CHECKOUT_PATH))
        clearAllMocks()

        every {
            userDetailsProvider.getAuthenticatedUser()
        } returns User(username = "aurora", token = "token", fullName = "Aurora Test User")
    }

    @Test
    fun `Throws exception when AuroraConfig cannot be found`() {

        assertThat {
            auroraConfigService.findAuroraConfig(
                AuroraConfigRef(
                    "no_such_affiliation",
                    "master",
                    "123"
                )
            )
        }.isFailure().all {
            isInstanceOf(IllegalArgumentException::class)
        }
    }

    @Test
    fun `Finds existing AuroraConfig by name`() {

        val auroraConfig = auroraConfigService.findAuroraConfig(ref)

        assertThat(auroraConfig).isNotNull()
        assertThat(auroraConfig.files.size).isEqualTo(0)
    }

    @Test
    fun `Save AuroraConfig`() {

        var auroraConfig = auroraConfigService.findAuroraConfig(ref)

        assertThat(auroraConfig.files.size).isEqualTo(0)

        auroraConfig = createAuroraConfig(defaultAuroraConfig())
        auroraConfigService.save(auroraConfig)
        auroraConfig = auroraConfigService.findAuroraConfig(ref)

        assertThat(auroraConfig.files.size).isEqualTo(4)
        assertThat(auroraConfig.files.map { it.name }).containsAll(
            "about.json",
            "utv/about.json",
            "utv/simple.json",
            "simple.json"
        )
    }

    @Test
    fun `allow yaml file`() {

        var auroraConfig = createAuroraConfig(defaultAuroraConfig())
        auroraConfigService.save(auroraConfig)

        val fooYaml = """certificate: true
        groupId: ske.aurora.openshift
        #this is a comment
        artifactId: simple
        name: simple
        version: 1.0.3
        route: true
        type: deploy
        """
        auroraConfig = createAuroraConfig(
            mapOf(
                "about.json" to DEFAULT_ABOUT,
                "utv/about.json" to DEFAULT_UTV_ABOUT,
                "foo.yaml" to fooYaml,
                "utv/foo.json" to """{ }"""
            )
        )
        auroraConfigService.save(auroraConfig)

        auroraConfig = auroraConfigService.findAuroraConfig(ref)

        assertThat(auroraConfig.files.size).isEqualTo(4)
        assertThat(auroraConfig.files.map { it.name }).containsAll(
            "foo.yaml",
            "utv/foo.json"
        )
    }

    @Test
    fun `Delete file from AuroraConfig`() {

        var auroraConfig = createAuroraConfig(defaultAuroraConfig())
        auroraConfigService.save(auroraConfig)

        auroraConfig = createAuroraConfig(
            mapOf(

                "about.json" to DEFAULT_ABOUT,
                "utv/about.json" to DEFAULT_UTV_ABOUT
            )
        )
        auroraConfigService.save(auroraConfig)

        auroraConfig = auroraConfigService.findAuroraConfig(ref)

        assertThat(auroraConfig.files.size).isEqualTo(2)
        assertThat(auroraConfig.files.map { it.name }).containsAll(
            "about.json",
            "utv/about.json"
        )
    }
}
