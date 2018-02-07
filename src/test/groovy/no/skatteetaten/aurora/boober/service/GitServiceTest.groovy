package no.skatteetaten.aurora.boober.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.controller.security.User
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.revwalk.RevCommit
import spock.lang.Ignore
import spock.lang.Specification

class GitServiceTest extends Specification {

  static REMOTE_REPO_FOLDER = new File("build/gitrepos_gitservice_bare").absoluteFile.absolutePath
  static CHECKOUT_PATH = new File("build/gitservice").absoluteFile.absolutePath
  static REPO_NAME = "test"

  def userDetailsProvider = Mock(UserDetailsProvider)
  def gitService = new GitService(userDetailsProvider, "$REMOTE_REPO_FOLDER/%s", CHECKOUT_PATH, "", "",
      new AuroraMetrics(new SimpleMeterRegistry()))

  def setup() {
    GitServiceHelperKt.recreateRepo(new File(REMOTE_REPO_FOLDER, "${REPO_NAME}.git"))
    FolderHelperKt.recreateFolder(new File(CHECKOUT_PATH))

    userDetailsProvider.getAuthenticatedUser() >> new User("aurora", "token", "Aurora Test User", [])
  }

  @Ignore
  //does not work on mac
  def "Verify local unpushed commits are deleted when checking out repo"() {

    final String USER1_FOLDER = "test_user1"
    final String USER2_FOLDER = "test_user2"

    given: "A git repository with a few commits by user1"
      Git repoUser1 = gitService.checkoutRepository(REPO_NAME, USER1_FOLDER)
      def testFile = new File(CHECKOUT_PATH, "$USER1_FOLDER/test.txt")

      ["First", "Second", "Third"].each {
        testFile.write(it)
        gitService.commitAndPushChanges(repoUser1, "$it commit")
      }

    and: "repository has been checked out by user2"
      gitService.checkoutRepository(REPO_NAME, USER2_FOLDER)

    when: "user1 resets the last commit and force pushes that change"
      repoUser1.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD~1").call()
      repoUser1.push().setForce(true).call()

    and: "when user2 checks out again over the previous checkout"
      Git repoUser2 = gitService.checkoutRepository(REPO_NAME, USER2_FOLDER)

    then: "the deleted commit by user1 should be gone (this is not default git behaviour)"
      List<RevCommit> commits = repoUser2.log().all().call().asList()
      commits*.fullMessage.containsAll("First commit", "Second commit")
      commits.size() == 2
  }
}
