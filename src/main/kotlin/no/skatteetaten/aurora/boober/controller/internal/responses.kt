package no.skatteetaten.aurora.boober.controller.internal

data class Response(
    val success: Boolean = true,
    val message: String = "OK",
    val items: List<Any>,
    val count: Int = items.size
)

data class KeyValueResponse<T>(
    val success: Boolean = true,
    val message: String = "OK",
    val items: Map<String, T>,
    val count: Int = items.size
)

data class SingleResponse<T>(
    val success: Boolean = true,
    val message: String = "OK",
    val items: T,
    val count: Int = 1
)