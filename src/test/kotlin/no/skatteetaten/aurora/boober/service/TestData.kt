package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraDeploy
import no.skatteetaten.aurora.boober.model.AuroraDeployStrategy
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

val auroraDevelopment = AuroraDeploymentSpec(
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
                secrets = emptyMap(),
                config = mapOf(),
                mounts = emptyList(),
                permissions = null
        ),
        route = AuroraRoute(emptyList()),
        deploy = AuroraDeploy(
                applicationFile = "boober-unit-test/dev-test.json",
                overrideFiles = emptyMap(),
                releaseTo = null,
                deployStrategy = AuroraDeployStrategy("rolling", 120),
                flags = AuroraDeploymentConfigFlags(
                        alarm = false,
                        debug = false,
                        cert = false),

                resources = AuroraDeploymentConfigResources(
                        memory = AuroraDeploymentConfigResource("128mi", "128mi"),
                        cpu = AuroraDeploymentConfigResource("0", "2000")),

                replicas = 1,
                groupId = "ske.aurora.openshift.referanse",
                artifactId = "openshift-referanse-springboot-server",
                version = "0.0.89",
                splunkIndex = "openshift-test",
                database = listOf(Database("referanseapp")),
                liveness = Probe(null, 8080, 10, 1),
                readiness = Probe(null, 8080, 10, 1),
                dockerImagePath = "foo",
                dockerTag = "bar"
        )
)


