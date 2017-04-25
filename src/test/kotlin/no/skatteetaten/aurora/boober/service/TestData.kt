package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.*

val auroraDcDevelopment = AuroraDeploymentConfig(
        schemaVersion = "v1",
        affiliation = "aurora",
        cluster = "utv",
        type = TemplateType.development,
        config = mapOf(),
        flags = AuroraDeploymentConfigFlags(route = true,
                alarm = false,
                debug = false,
                rolling = true,
                cert = false),

        resources = AuroraDeploymentConfigResources(
                memory = AuroraDeploymentConfigResource("128mi", "128mi"),
                cpu = AuroraDeploymentConfigResource("0", "2000")),
        artifactId = "openshift-referanse-springboot-server",
        groupId = "ske.aurora.openshift.referanse",
        version = "0.0.89",
        database = "referanseapp",
        splunkIndex = "openshift-test",
        envName = "boober-unit-test",
        permissions = Permissions(
                admin = Permission(
                        groups = setOf("foo", "bar"),
                        users = setOf("m123", "x123bar"))),
        name = "dev-test",
        replicas = 1,
        secrets = emptyMap(),
        extraTags = ""
)

/*
fun generateProccessADC(template: Map<String, Any?>) =
        AuroraDeploymentConfig(
                affiliation = "aurora",
                cluster = "utv",
                type = TemplateType.process,
                deployDescriptor = TemplateDeploy(
                        templateFile = template,
                        parameters = mapOf(
                                "SPLUNK_INDEX" to " safir-test",
                                "APP_NAME" to "tvinn",
                                "FEED_NAME" to "tolldeklarasjon",
                                "DOMAIN_NAME" to "localhost",
                                "DB_NAME" to "tvinn",
                                "AFFILIATION" to "safir"
                        )
                ),
                envName = "boober-unit-test",
                groups = setOf("APP_PaaS_drift", "APP_PaaS_utv"),
                name = "dev-test",
                users = setOf("foo"),
                secrets = emptyMap(),
                config = emptyMap(),
                deploymentStrategy = DeploymentStrategy.rolling,
                replicas = 1
        )*/
