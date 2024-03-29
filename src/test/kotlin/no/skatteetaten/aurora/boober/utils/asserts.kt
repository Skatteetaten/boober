package no.skatteetaten.aurora.boober.utils

import assertk.Assert
import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isSuccess
import assertk.assertions.messageContains
import assertk.assertions.support.expected
import assertk.assertions.support.show
import no.skatteetaten.aurora.boober.model.AuroraConfigException
import no.skatteetaten.aurora.boober.model.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.model.InvalidDeploymentContext
import no.skatteetaten.aurora.boober.service.MultiApplicationDeployValidationResultException
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationResultException
import java.nio.charset.Charset
import java.util.Base64

fun Assert<Result<Pair<List<AuroraDeploymentContext>, List<InvalidDeploymentContext>>>>.singleApplicationErrorResult(
    expectedMessage: String
) {
    this.isSuccess()
        .transform { mae ->
            val errors = mae.second.map { it.errors }
            if (errors.size > 1) {
                throw IllegalArgumentException("More than one error")
            } else if (errors.isEmpty()) {
                throw IllegalArgumentException("Does not contain any errors")
            } else {
                errors.first().errors.first()
            }
        }
        .messageContains(expectedMessage)
}

fun <T> Assert<Result<T>>.singleApplicationError(expectedMessage: String) {
    this.isFailure()
        .isInstanceOf(MultiApplicationValidationException::class)
        .transform { mae ->
            val errors = mae.errors.flatMap { it.errors }
            if (errors.size != 1) {
                throw mae
            } else {
                errors.first()
            }
        }
        .messageContains(expectedMessage)
}

fun <T> Assert<Result<T>>.singleApplicationValidationError(expectedMessage: String) {
    this.isFailure()
        .isInstanceOf(MultiApplicationValidationResultException::class)
        .transform { mae ->
            if (mae.invalid.size != 1) {
                throw mae
            } else mae.invalid.first().errors.errors.first()
        }
        .messageContains(expectedMessage)
}

fun <T> Assert<Result<T>>.singleApplicationDeployError(expectedMessage: String) {
    this.isFailure()
        .isInstanceOf(MultiApplicationDeployValidationResultException::class)
        .transform { mae ->
            if (mae.invalid.size != 1) {
                throw mae
            } else {
                mae.invalid.first().errors.first()
            }
        }
        .messageContains(expectedMessage)
}

fun Assert<Result<Pair<List<AuroraDeploymentContext>, List<InvalidDeploymentContext>>>>.applicationErrorResult(
    vararg message: String
) {
    this.isSuccess()
        .transform { mae ->
            val errors = mae.second.first().errors.errors.map { it.localizedMessage }

            errors.zip(message.toList()).forEach { (actual, expected) ->
                println("$actual  :   $expected")
                if (!actual.contains(expected)) {
                    this.expected(":${show(actual)} to contain:${show(expected)}")
                }
            }
        }
}

fun <T> Assert<Result<T>>.configErrors(messages: List<String>) {
    this.isFailure()
        .isInstanceOf(AuroraConfigException::class)
        .transform { ace ->
            val errors = ace.errors.map { it.message }.distinct()
            assertThat(errors).containsExactlyInAnyOrder(*messages.toTypedArray())
        }
}

internal fun assertThatBase64Decoded(str: String?) =
    assertThat(Base64.getDecoder().decode(str).toString(Charset.defaultCharset()))
