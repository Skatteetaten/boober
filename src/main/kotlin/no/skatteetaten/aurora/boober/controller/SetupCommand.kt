package no.skatteetaten.aurora.boober.controller

typealias AuroraConfigSources = Map<String, Map<String, Any?>>

data class SetupCommand(val affiliation: String,
                        val envs: List<String> = listOf(),
                        val apps: List<String> = listOf(),
                        val files: AuroraConfigSources = mapOf(),
                        val secretFiles: Map<String, String> = mapOf(),
                        val overrides: AuroraConfigSources = mapOf())