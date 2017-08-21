package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigDeploy
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigFlags
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigProcessLocalTemplate
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigResource
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigResources
import no.skatteetaten.aurora.boober.model.Database
import no.skatteetaten.aurora.boober.model.Permission
import no.skatteetaten.aurora.boober.model.Permissions
import no.skatteetaten.aurora.boober.model.Probe
import no.skatteetaten.aurora.boober.model.Route

import no.skatteetaten.aurora.boober.model.TemplateType

val auroraDcDevelopment = AuroraDeploymentConfigDeploy(
        schemaVersion = "v1",
        affiliation = "aurora",
        cluster = "utv",
        type = TemplateType.development,
        config = mapOf(),
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
        envName = "boober-unit-test",
        permissions = Permissions(
                admin = Permission(
                        groups = setOf("foo", "bar"),
                        users = setOf("m123", "x123bar"))),
        name = "dev-test",
        replicas = 1,
        secrets = emptyMap(),
        extraTags = "",
        route = emptyList(),
        fields = emptyMap(),
        unmappedPointers = emptyMap(),
        applicationFile = "boober-unit-test/dev-test.json",
        overrideFiles = emptyMap(),
        liveness = Probe(null, 8080, 10, 1),
        readiness = Probe(null, 8080, 10, 1)
)


fun generateProccessADC(template: JsonNode) =
        AuroraDeploymentConfigProcessLocalTemplate(
                affiliation = "aurora",
                cluster = "utv",
                type = TemplateType.localTemplate,
                permissions = Permissions(
                        admin = Permission(
                                groups = setOf("APP_PaaS_drift", "APP_PaaS_utv"),
                                users = setOf("foo"))),
                templateJson = template,
                parameters = mapOf(
                        "SPLUNK_INDEX" to " safir-test",
                        "APP_NAME" to "tvinn",
                        "FEED_NAME" to "tolldeklarasjon",
                        "DOMAIN_NAME" to "localhost",
                        "DB_NAME" to "tvinn",
                        "AFFILIATION" to "safir"
                )
                ,
                envName = "boober-unit-test",
                name = "dev-test",
                secrets = emptyMap(),
                config = emptyMap(),
                route = emptyList(),
                fields = emptyMap(),
                unmappedPointers = emptyMap(),
                applicationFile = "boober-unit-test/dev-test.json",
                overrideFiles = emptyMap())
