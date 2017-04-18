package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraDeploy
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.model.DeploymentStrategy
import no.skatteetaten.aurora.boober.model.TemplateType

val auroraDcDevelopment = AuroraDeploymentConfig(
        affiliation = "aurora",
        cluster = "utv",
        type = TemplateType.development,
        config = mapOf(),
        deploymentStrategy = DeploymentStrategy.rolling,
        deployDescriptor = AuroraDeploy(
                artifactId = "openshift-referanse-springboot-server",
                groupId = "ske.aurora.openshift.referanse",
                version = "0.0.89",
                alarm = false,
                certificateCn = null,
                cpuRequest = "100",
                database = "referanseapp",
                debug = false,
                extraTags = null,
                managementPath = null,
                maxMemory = "128M",
                prometheus = null,
                splunkIndex = "openshift-test",
                tag = null,
                websealRoles = null,
                websealRoute = null

        ),
        envName = "boober-unit-test",
        groups = setOf("foo", "bar"),
        name = "dev-test",
        replicas = 1,
        route = true,
        users = setOf("m123", "x123bar"),
        secrets = emptyMap()
)