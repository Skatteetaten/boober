package no.skatteetaten.aurora.boober.utils

import assertk.Assert
import assertk.Result
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isSuccess
import assertk.assertions.messageContains
import assertk.assertions.support.expected
import assertk.assertions.support.show
import no.skatteetaten.aurora.boober.model.AuroraConfigException
import no.skatteetaten.aurora.boober.model.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.service.ContextErrors
import no.skatteetaten.aurora.boober.service.MultiApplicationDeployValidationResultException
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationResultException

fun Assert<Result<Pair<List<AuroraDeploymentContext>, List<Pair<AuroraDeploymentContext?, ContextErrors?>>>>>.singleApplicationErrorResult(expectedMessage: String) {
    this.isSuccess()
        .transform { mae ->
            val errors = mae.second.map { it.second }
            if (errors.size > 1 && errors.isNotEmpty()) {
                throw IllegalArgumentException("More than one error")
            } else {
                errors.first()!!.errors.first()
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
            } else mae.invalid.first().second!!.errors.first()
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

fun <T> Assert<Result<T>>.applicationErrors(vararg message: String) {
    this.applicationErrors(message.toList())
}

fun <T> Assert<Result<T>>.applicationErrors(messages: List<String>) {
    this.isFailure()
        .isInstanceOf(MultiApplicationValidationException::class)
        .transform { mae ->
            val errors = mae.errors.flatMap { it.errors }
            if (errors.size != messages.size) {
                this.expected("You do not expect all error messages. Actual error messages are ${errors.size}")
            }
            errors.zip(messages).forEach { (actual, expected) ->
                if (!actual.localizedMessage.contains(expected)) {
                    this.expected(":${show(actual.localizedMessage)} to contain:${show(expected)}")
                }
            }
        }
}

fun <T> Assert<Result<T>>.configErrors(messages: List<String>) {
    this.isFailure()
        .isInstanceOf(AuroraConfigException::class)
        .transform { ace ->
            val errors = ace.errors.map { it.asWarning() }.distinct()

            if (errors.size != messages.size) {
                this.expected("You do not expect all error messages. Actual error messages are ${errors.size}")
            }
            errors.zip(messages).forEach { (actual, expected) ->
                if (!actual.contains(expected)) {
                    this.expected(":${show(actual)} to contain:${show(expected)}")
                }
            }
        }
}
