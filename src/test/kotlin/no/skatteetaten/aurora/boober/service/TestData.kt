package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.*

val auroraDevelopment = AuroraApplicationConfig(
        schemaVersion = "v1",
        affiliation = "aurora",
        cluster = "utv",
        type = TemplateType.development,
        fields = emptyMap(),
        unmappedPointers = emptyMap(),
        envName = "boober-unit-test",
        permissions = Permissions(
                admin = Permission(
                        groups = setOf("foo", "bar"),
                        users = setOf("m123", "x123bar"))),

        name = "dev-test",
        volume = AuroraVolume(
                config = mapOf(),
                secrets = emptyMap(),
                route = emptyList(),
                mounts = emptyList()

        ),
        deploy = AuroraDeploy(
                releaseTo = null,
                applicationFile = "boober-unit-test/dev-test.json",
                overrideFiles = emptyMap(),
                flags = AuroraDeploymentConfigFlags(
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
                database = listOf(Database("referanseapp")),
                splunkIndex = "openshift-test",
                replicas = 1,
                liveness = Probe(null, 8080, 10, 1),
                readiness = Probe(null, 8080, 10, 1),
                dockerImagePath = "foo",
                dockerTag = "bar"
        )
)


