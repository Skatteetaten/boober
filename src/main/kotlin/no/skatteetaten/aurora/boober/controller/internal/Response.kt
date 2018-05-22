package no.skatteetaten.aurora.boober.controller.internal

data class Response(
    val success: Boolean = true,
    val message: String = "OK",
    val items: List<Any>,
    val count: Int = items.size
)