package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isFailure
import assertk.assertions.messageContains
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.service.AuroraTemplateService
import no.skatteetaten.aurora.boober.service.BitbucketService
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import org.junit.jupiter.api.Test

class AuroraTemplatesServiceTest : ResourceLoader() {

    val bitbucketService: BitbucketService = mockk()

    private val project = "project"
    private val repo = "repo"
    private val reference = "master"

    private val service = AuroraTemplateService(
        bitbucketService = bitbucketService,
        templateProject = project,
        templateRepo = repo,
        templatesRef = reference
    )

    @Test
    fun `Should return error if template does not exist`() {

        every { bitbucketService.getFile(project, repo, "atomhopper.json", reference) } returns null

        assertThat {
            service.findTemplate("atomhopper")
        }.isFailure().messageContains("Could not find template=atomhopper")
    }

    @Test
    fun `Should return error if bitbucket throws error`() {

        every {
            bitbucketService.getFile(
                project,
                repo,
                "atomhopper.json",
                reference
            )
        } throws Exception("Could not connect to bitbucket")

        assertThat {
            service.findTemplate("atomhopper")
        }.isFailure().messageContains("Error fetching template=atomhopper message=Could not connect to bitbucket")
    }

    @Test
    fun `Should return error if template is not valid json`() {

        every { bitbucketService.getFile(project, repo, "atomhopper.json", reference) } returns "{"

        assertThat {
            service.findTemplate("atomhopper")
        }.isFailure().messageContains("Could not parse template as json")
    }
}
