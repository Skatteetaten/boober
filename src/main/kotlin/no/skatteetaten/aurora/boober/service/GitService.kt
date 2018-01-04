package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.utils.LambdaOutputStream
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.TextProgressMonitor
import org.eclipse.jgit.revwalk.RevTag
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.io.File
import java.io.PrintWriter

@Configuration
class GitServices(
        val userDetails: UserDetailsProvider,
        val metrics: AuroraMetrics
) {

    enum class Domain {
        AURORA_CONFIG,
        VAULT
    }

    @Target(AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.RUNTIME)
    @Qualifier
    annotation class TargetDomain(val value: Domain)

    @Bean
    @TargetDomain(Domain.AURORA_CONFIG)
    @Primary
    fun auroraConfigGitService(@Value("\${boober.git.urlPattern}") url: String,
                               @Value("\${boober.git.checkoutPath}") checkoutPath: String,
                               @Value("\${boober.git.username}") username: String,
                               @Value("\${boober.git.password}") password: String): GitService {
        return GitService(userDetails, url, checkoutPath, username, password, metrics)
    }

    @Bean
    @TargetDomain(Domain.VAULT)
    fun vaultGitService(@Value("\${vault.git.urlPattern}") url: String,
                        @Value("\${vault.git.checkoutPath}") checkoutPath: String,
                        @Value("\${vault.git.username}") username: String,
                        @Value("\${vault.git.password}") password: String): GitService {
        return GitService(userDetails, url, checkoutPath, username, password, metrics)
    }
}

open class GitService(
        val userDetails: UserDetailsProvider,
        val url: String,
        val checkoutPath: String,
        val username: String,
        val password: String,
        val metrics: AuroraMetrics) {

    val logger: Logger = LoggerFactory.getLogger(GitService::class.java)

    val cp = UsernamePasswordCredentialsProvider(username, password)

    // Try to delete this. Only used in tests.
    fun deleteFiles(affiliation: String) {
        val repoDir = File(checkoutPath + "/" + affiliation)
        if (repoDir.exists()) {
            repoDir.deleteRecursively()
        }
    }

    //only test
    fun openRepo(affiliation: String): Git {
        val repoPath = File("$checkoutPath/$affiliation")
        return Git.open(repoPath)
    }

    fun checkoutRepository(repositoryName: String): Git {
        val repoPath = File(File("$checkoutPath/$repositoryName").absoluteFile.absolutePath)
        if (repoPath.exists()) {
            try {
                val git = Git.open(repoPath)
                git.pull()
                        .setRebase(true)
                        .setRemote("origin")
                        .setCredentialsProvider(cp)
                        .call()
                return git
            } catch (e: Exception) {
                repoPath.deleteRecursively()
            }
        }
        return metrics.withMetrics("git_checkout", {
            val dir = repoPath.apply { mkdirs() }
            val uri = url.format(repositoryName)

            try {
                Git.cloneRepository()
                        .setURI(uri)
                        .setCredentialsProvider(cp)
                        .setDirectory(dir)
                        .call()
            } catch (ex: Exception) {
                dir.deleteRecursively()
                throw ex
            }
        })
    }

    fun closeRepository(repo: Git) {
        repo.close()
    }

    //TODO: move out to another abstraction
    fun getAllAuroraConfigFiles(git: Git): Map<String, File> {
        return getAllFiles(git)
    }


    fun pushTags(git: Git, tags: List<Ref>) {

        logger.debug("push tags to git")
        val cmd = git.push()
        logger.debug("added tag")
        tags.forEach { cmd.add(it) }
        logger.debug("/added tag")
        metrics.withMetrics("git_push_tags", {
            cmd.setCredentialsProvider(cp)
            if (logger.isDebugEnabled) {
                cmd.progressMonitor = TextProgressMonitor(PrintWriter(LambdaOutputStream {
                    logger.debug(it)
                }))
            }
            cmd.call()
        })
        logger.debug("/push tags to git")
    }


    fun markRelease(git: Git, tag: String, tagBody: String): Ref {
        val user = userDetails.getAuthenticatedUser().let { PersonIdent(it.fullName ?: it.username, "${it.username}@skatteetaten.no") }
        return git.tag().setTagger(user).setAnnotated(true).setName(tag).setMessage(tagBody).call()
    }

    fun readTag(git: Git, oid: ObjectId): RevTag? {

        val objectLoader = git.repository.open(oid)

        val bytes = objectLoader.getCachedBytes(Int.MAX_VALUE)

        return RevTag.parse(bytes)

    }

    fun tagHistory(git: Git): List<RevTag> {
        val tags = git.tagList().call()
        return tags.mapNotNull { readTag(git, it.objectId) }
    }


    //TODO: This and the one below must be joined together somehow.
/*
    fun getFile(git: Git, fileName: String): File? {

        val folder = git.repository.directory.parentFile
        return folder.walkBottomUp()
                .onEnter { !it.name.startsWith(".git") }
                .filter { it.isFile && it.relativeTo(folder).path == fileName }
                .map {
                    val path = it.relativeTo(folder).path
                    it
                }.firstOrNull()
    }
*/


    private fun getAllFiles(git: Git): Map<String, File> {

        val folder = git.repository.directory.parentFile
        val files = folder.walkBottomUp()
                //TODO: Ignore all files starting with . not just .git
                .onEnter { !it.name.startsWith(".git") }
                .filter { it.isFile }
                .associate {
                    it.relativeTo(folder).path to it
                }
        return files
    }

}
