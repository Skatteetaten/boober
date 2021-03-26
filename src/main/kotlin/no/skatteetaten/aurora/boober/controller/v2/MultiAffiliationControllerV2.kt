package no.skatteetaten.aurora.boober.controller.v2

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.facade.AuroraConfigFacade
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.utils.parallelMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v2/multiaffiliation")
class MultiAffiliationControllerV2(
    private val auroraConfigFacade: AuroraConfigFacade
) {

    @GetMapping("/{environment}")
    fun get(@PathVariable environment: String): Response {

        val affiliationsWithEnviroment = auroraConfigFacade.findAllAuroraConfigNames()
            .filter { it.equals("aurora") } // remove this
            .parallelMap {
                println("""affiliation:$it and environment:$environment""")

                val envName =
                    auroraConfigFacade.auroraConfigHasEnvironmentWithName(AuroraConfigRef(it, "feature/AOS-5477_hente_miljo_paa_tvers"), environment)

                if (envName) {
                    it
                } else {
                    null
                }
            }.filterNotNull()

        return Response(affiliationsWithEnviroment)
    }
}
