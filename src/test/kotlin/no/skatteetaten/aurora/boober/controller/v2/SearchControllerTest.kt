package no.skatteetaten.aurora.boober.controller.v2

import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import no.skatteetaten.aurora.boober.controller.v1.AbstractControllerTest
import no.skatteetaten.aurora.boober.facade.ApplicationSearchResult
import no.skatteetaten.aurora.boober.facade.AuroraConfigFacade
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk

@WebMvcTest(controllers = [SearchController::class])
class SearchControllerTest : AbstractControllerTest() {

    @MockkBean
    private lateinit var facade: AuroraConfigFacade

    @Test
    fun `Search for environment by name`() {

        every {
            facade.searchForApplications(any(), any())
        } returns listOf(
            ApplicationSearchResult(
                affiliation = "myAffiliation",
                autoDeploy = true,
                applicationDeploymentRef = ApplicationDeploymentRef("env", "someApp"),
                warningMessage = "This is waring"
            ),
            ApplicationSearchResult(
                affiliation = "myAffiliation",
                autoDeploy = true,
                applicationDeploymentRef = ApplicationDeploymentRef("env", "someApp2"),
                errorMessage = "Some error"
            )
        )

        mockMvc.get(path = Path("/v2/search?environment={name}", "fk1-utv")) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.count").equalsValue(2)
            responseJsonPath("$.items.length()").equalsValue(1)
            responseJsonPath("$.errors.length()").equalsValue(1)
        }
    }
}
