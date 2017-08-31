package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraApplicationConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploy
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigFlags
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigResource
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigResources
import no.skatteetaten.aurora.boober.model.AuroraRoute
import no.skatteetaten.aurora.boober.model.AuroraVolume
import no.skatteetaten.aurora.boober.model.Database
import no.skatteetaten.aurora.boober.model.Permission
import no.skatteetaten.aurora.boober.model.Permissions
import no.skatteetaten.aurora.boober.model.Probe
import no.skatteetaten.aurora.boober.model.TemplateType

val auroraDevelopment = AuroraApplicationConfig(
        schemaVersion = "v1",
        affiliation = "aurora",
        cluster = "utv",
        type = TemplateType.development,
        fields = emptyMap(),
        envName = "boober-unit-test",
        permissions = Permissions(
                admin = Permission(
                        groups = setOf("foo", "bar"),
                        users = setOf("m123", "x123bar"))),

        name = "dev-test",
        volume = AuroraVolume(
                config = mapOf(),
                secrets = emptyMap(),
                mounts = emptyList()

        ),
        route = AuroraRoute(emptyList()),
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


