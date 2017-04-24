package no.skatteetaten.aurora.boober.controller

typealias Overrides = Map<String, Map<String, Any?>>

data class SetupCommand(val affiliation: String,
                        val envs: List<String> = listOf(),
                        val apps: List<String> = listOf(),
                        val files: Map<String, Map<String, Any?>> = mapOf(),
                        val secretFiles: Map<String, String> = mapOf(),
                        val overrides: Overrides = mapOf())