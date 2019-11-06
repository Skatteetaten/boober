package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import java.io.File
import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import no.skatteetaten.aurora.boober.utils.ZipUtils
import no.skatteetaten.aurora.boober.utils.jsonMapper
import no.skatteetaten.aurora.boober.utils.recreateFolder
import no.skatteetaten.aurora.boober.utils.recreateRepo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GitServiceTest : ResourceLoader() {

    val REMOTE_REPO_FOLDER = File("build/gitrepos_gitservice_bare").absoluteFile.absolutePath
    val CHECKOUT_PATH = File("build/gitservice").absoluteFile.absolutePath
    val REPO_NAME = "test"
    val BRANCH_NAME = "master"

    val userDetailsProvider = mockk<UserDetailsProvider>()
    val gitService = GitService(
        userDetailsProvider, "$REMOTE_REPO_FOLDER/%s", CHECKOUT_PATH, "", "",
        AuroraMetrics(SimpleMeterRegistry())
    )

    @BeforeEach
    fun setup() {
        recreateRepo(File(REMOTE_REPO_FOLDER, "$REPO_NAME.git"))
        recreateFolder(File(CHECKOUT_PATH))
        every { userDetailsProvider.getAuthenticatedUser() } returns User("aurora", "token", "Aurora Test User")
    }

    @Test
    fun `Verify checking out repository with refName`() {
        val TEST_REPO_NAME = "boober-test"
        val remoteRepoFolder = File(REMOTE_REPO_FOLDER)
        ZipUtils.unzip(File(getResourceUrl("$TEST_REPO_NAME.zip").file), remoteRepoFolder)
        val localGitService = GitService(
            userDetails = userDetailsProvider,
            urlPattern = "${remoteRepoFolder.absolutePath}/%s.git",
            checkoutPath = CHECKOUT_PATH,
            username = "",
            password = "",
            metrics = AuroraMetrics(SimpleMeterRegistry())
        )

        val git = localGitService.checkoutRepository(TEST_REPO_NAME, "master")
        val masterConsoleFile: JsonNode = jsonMapper().readValue(File("$CHECKOUT_PATH/$TEST_REPO_NAME/console.json"))
        assertThat(git.repository.branch).isEqualTo("master")
        assertThat(masterConsoleFile.get("version").textValue()).isEqualTo("3")

        val localGit = localGitService.checkoutRepository(TEST_REPO_NAME, "develop")
        val developConsoleFile: JsonNode = jsonMapper().readValue(File("$CHECKOUT_PATH/$TEST_REPO_NAME/console.json"))

        assertThat(localGit.repository.branch).isEqualTo("develop")
        assertThat(developConsoleFile.get("version").textValue()).isEqualTo("4")
    }
}
