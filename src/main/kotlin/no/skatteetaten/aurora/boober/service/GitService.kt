package no.skatteetaten.aurora.boober.service

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File


//@Service
class GitService(
        @Value("\${boober.git.url}") val url: String,
        @Value("\${boober.git.username}") val username: String,
        @Value("\${boober.git.password}") val password: String) {


    val cp = UsernamePasswordCredentialsProvider(username, password)


    //dette er Det vi skal gjøre når det blir kjørt en setup kommando. resourceVersion får vi først etter at kommando er kjørt
    //så dette må gjøres etter at vi har installert objektene. Det er resourceVersion i DC vi hovedsakelig bryr oss om.
    //men hva med de tilfellene hvor vi ikke endrer dc men f.eks bare endrer en configMap? Da vil vi jo ikke ha resourceVersion på dc være endret.
    //må vi faktisk ha med en annotated tag for hver ressurstype vi endrer?
    /*

    så f.eks hvis en boober setup endrer en configMap så må vi hente ned resourceVersion etterpå og tagge med namespace-name-resourcetype-resourceVersion?
    Vi har jo sagt at dette apiet kun skal applye det som faktisk er endret. så hvis vi applyer en configmap og den ikke endret så får vi vel samme resourceVersion og da skal jo
    ikke denne taggen flyttes?
     */
    fun markRelease(git: Git, namespace: String, name: String, resourceVersion: Int, commit: RevCommit) {


        git.branchCreate()
                .setForce(true)
                .setName("$namespace-$name")
                .setStartPoint(commit)
                .call()

        val tag = "$namespace-$name-$resourceVersion"
        git.tag().setAnnotated(true).setName(tag).setMessage(tag).call()


    }

    fun get(dir: File): Git {


        /*Det vi egentlig vil her er vel ca følgende flyt
         Alle operasjoner vi gjør skal første sørge for at vi har siste kommando fra upstream.
         så skal det utføre sin operasjon, pushe det upstream og så lukke ressursen. Skal kun pushe hvis man har endringer


        */
        //TODO:feilhåtering. Må huske å lukke git repo

        val git = if (!dir.exists()) {
            dir.mkdir()


            Git.cloneRepository()
                    .setURI("$url/aot.git")
                    .setCredentialsProvider(cp)
                    .setDirectory(dir)
                    .call()
        } else {
            val git = Git.open(dir)
            git.pull().setCredentialsProvider(cp).call()
            //TODO:Hvis konflikt her må vi gjøre noe, skal ikke skje men du vet aldri
            git
        }


        return git

    }

}
