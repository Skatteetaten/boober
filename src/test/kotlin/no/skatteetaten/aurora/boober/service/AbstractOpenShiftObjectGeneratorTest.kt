package no.skatteetaten.aurora.boober.service

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.model.AbstractAuroraConfigTest
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef.Companion.adr
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.utils.Instants
import no.skatteetaten.aurora.boober.utils.jsonMapper
import org.junit.jupiter.api.BeforeEach
import java.time.Instant

open class AbstractOpenShiftObjectGeneratorTest : AbstractAuroraConfigTest() {

    val DEPLOY_ID = "123"

    val userDetailsProvider = mockk<UserDetailsProvider>()

    val openShiftResourceClient = mockk<OpenShiftResourceClient>()
    val mapper = jsonMapper()

    @BeforeEach
    fun setup() {
        clearAllMocks()
    }

    fun createObjectGenerator(username: String = "aurora"): OpenShiftObjectGenerator {

        Instants.determineNow = { Instant.EPOCH }
        every { userDetailsProvider.getAuthenticatedUser() } returns User(username, "token", "Aurora OpenShift")

        val templateProcessor = OpenShiftTemplateProcessor(userDetailsProvider, openShiftResourceClient, mapper)

        return OpenShiftObjectGenerator(
            dockerRegistry = "docker-registry.aurora.sits.no:5000",
            openShiftObjectLabelService = OpenShiftObjectLabelService(userDetailsProvider),
            mapper = mapper,
            openShiftTemplateProcessor = templateProcessor,
            openShiftClient = openShiftResourceClient,
            routeSuffix = ".utv.paas.skead.no"
        )
    }

    fun specJavaWithToxiproxy(): AuroraDeploymentSpecInternal {
        return createDeploymentSpec(
            mapOf(
                "about.json" to DEFAULT_ABOUT,
                "utv/about.json" to DEFAULT_UTV_ABOUT,
                "reference.json" to REF_APP_JSON,
                "utv/reference.json" to """{ "toxiproxy" : { "version" : "2.1.3" } }"""
            ), adr("utv", "reference")
        )
    }

    fun specWebWithToxiproxy(): AuroraDeploymentSpecInternal {
        return createDeploymentSpec(
            mapOf(
                "about.json" to DEFAULT_ABOUT,
                "utv/about.json" to DEFAULT_UTV_ABOUT,
                "webleveranse.json" to WEB_LEVERANSE,
                "utv/webleveranse.json" to """{ "type": "deploy", "version" : "1.0.8", "database" : { "REFerence" : "auto" }, "toxiproxy" : { "version" : "2.1.3" } }"""
            ), adr("utv", "webleveranse")
        )
    }
}
