package no.skatteetaten.aurora.boober.facade

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
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

    val auroraConfigRef = AuroraConfigRef("paas", "master")
    val auroraConfig = getAuroraConfigSamples()

    @BeforeEach
    fun beforeEach() {
        every { auroraConfigService.findAuroraConfig(auroraConfigRef) } returns auroraConfig
    }

    val adr = ApplicationDeploymentRef("utv", "simple")

    //Hvor mye verdi gir dette? Det trenger jo ikke være en spring boot test?
    @Test
    fun `Should find aurora spec`() {

        val result = facade.findAuroraDeploymentSpec(auroraConfigRef, listOf(adr))
        assertThat(result.size).isEqualTo(1)
        logger.info {
            jacksonObjectMapper().writeValueAsString(result.first())
        }
    }
}