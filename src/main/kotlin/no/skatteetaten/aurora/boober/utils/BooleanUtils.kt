package no.skatteetaten.aurora.boober.utils

fun <T> Boolean.whenTrue(fn: () -> T?): T? = this.takeIf { this }?.let { fn() }
fun <T> Boolean.whenFalse(fn: () -> T?): T? = this.takeUnless { this }?.let { fn() }
