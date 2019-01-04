package no.skatteetaten.aurora.boober.contracts

import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.v1.UserAnnotationControllerV1
import no.skatteetaten.aurora.boober.controller.v1.UserAnnotationResponder
import no.skatteetaten.aurora.boober.service.UserAnnotationService
import org.junit.jupiter.api.BeforeEach

open class UserannotationBase {

    @BeforeEach
    fun setUp() {
        withContractResponses(this) {
            val userAnnotationService = mockk<UserAnnotationService>(relaxed = true)
            val userAnnotationResponses = mockk<UserAnnotationResponder> {
                every { create(any()) } returns it.response()
            }
            UserAnnotationControllerV1(userAnnotationService, userAnnotationResponses)
        }
    }
}