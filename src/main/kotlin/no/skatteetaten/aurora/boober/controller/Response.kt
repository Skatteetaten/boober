package no.skatteetaten.aurora.boober.controller

data class Response(
        val message: String = "OK",
        val items: List<Any>,
        val count: Int = items.size
)