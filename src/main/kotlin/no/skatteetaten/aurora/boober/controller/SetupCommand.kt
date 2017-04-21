package no.skatteetaten.aurora.boober.controller

data class SetupCommand(val affiliation: String,
                        val envs: List<String> = listOf(),
                        val apps: List<String> = listOf(),
                        val files: Map<String, Map<String, Any?>>,
                        val secretFiles: Map<String, String> = mapOf(),
                        val overrides: Map<String, Map<String, Any?>>?)