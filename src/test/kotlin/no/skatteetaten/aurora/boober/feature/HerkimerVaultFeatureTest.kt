package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.service.HerkimerService
import no.skatteetaten.aurora.boober.service.ResourceClaimHerkimer
import no.skatteetaten.aurora.boober.service.ResourceHerkimer
import no.skatteetaten.aurora.boober.service.ResourceKind
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.HttpMock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class HerkimerVaultFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = HerkimerVaultFeature(
            herkimerService = herkimerService
        )

    private val herkimerService: HerkimerService = mockk()

    @AfterEach
    fun after() {
        HttpMock.clearAllHttpMocks()
    }

    @Test
    fun `should get validation error if two configurations bot single with same prefix`() {

        mockHerkimerDatabase(
            createResourceHerkimer("ski-postgres", mapOf("foo" to "bar", "bar" to "baz"))
        )

        assertThat {
            generateResources(
                """{ 
                "credentials": {
                    "ski" : {
                        "resourceKind": "PostgresDatabaseInstance",
                        "multiple" : false
                    },
                    "ski2" : {
                        "resourceKind": "PostgresDatabaseInstance",
                        "prefix" : "ski",
                        "multiple" : false
                    }
                }
           }""",
                createdResources = 1,
                resources = mutableSetOf(createEmptyDeploymentConfig())
            )
        }.singleApplicationError("More than one credential of type single(multiple=false) shares the same prefix=ski")
    }

    @Test
    fun `should get validation error if single and returns multiple responses`() {

        mockHerkimerDatabase(
            createResourceHerkimer("ski-postgres", mapOf("foo" to "bar", "bar" to "baz")),
            createResourceHerkimer("pros-postgres", mapOf("foo" to "baz", "bar" to "foo"))
        )

        assertThat {
            generateResources(
                """{ 
                "credentials": {
                    "ski" : {
                        "resourceKind": "PostgresDatabaseInstance"
                    }
                }
           }""",
                createdResources = 1,
                resources = mutableSetOf(createEmptyDeploymentConfig())
            )
        }.singleApplicationError("Configured credential=ski is configured as multiple=false, but 2 was returned")
    }

    @Test
    fun `should get validation error if both single and multiple with same prefix`() {

        mockHerkimerDatabase(
            createResourceHerkimer("ski-postgres", mapOf("foo" to "bar", "bar" to "baz"))
        )

        assertThat {
            generateResources(
                """{ 
                "credentials": {
                    "ski" : {
                        "resourceKind": "PostgresDatabaseInstance",
                        "multiple" : true
                    },
                    "ski2" : {
                        "resourceKind" : "PostgresDatabaseInstance",
                        "prefix" : "ski"
                    }
                }
           }""",
                createdResources = 1,
                resources = mutableSetOf(createEmptyDeploymentConfig())
            )
        }.singleApplicationError("The shared prefix=ski has been configured with both multiple=false and multiple=true.")
    }

    @Test
    fun `verify creating secret from herkimer`() {

        mockHerkimerDatabase(
            createResourceHerkimer("ski-postgres", mapOf("foo" to "bar", "bar" to "baz"))
        )

        val (dc, secret) = generateResources(
            """{ 
                "credentials": {
                    "ski" : {
                        "resourceKind": "PostgresDatabaseInstance",
                        "multiple" : true
                    }
                }
           }""",
            createdResources = 1,
            resources = mutableSetOf(createEmptyDeploymentConfig())
        )
        assertThat(dc).auroraResourceMatchesFile("dc-multiple-single.json")
        assertThat(secret).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("secret-ski.json")
    }

    @Test
    fun `verify creating secret from herkimer two results`() {

        mockHerkimerDatabase(
            createResourceHerkimer("ski-postgres", mapOf("foo" to "bar", "bar" to "baz")),
            createResourceHerkimer("pros-postgres", mapOf("foo" to "baz", "bar" to "foo"))
        )

        val (dc, secret1, secret2) = generateResources(
            """{ 
                "credentials": {
                    "ski" : {
                        "resourceKind": "PostgresDatabaseInstance",
                        "multiple" : true
                    }
                }
           }""",
            createdResources = 2,
            resources = mutableSetOf(createEmptyDeploymentConfig())
        )
        assertThat(dc).auroraResourceMatchesFile("dc-multiple-multiple.json")
        assertThat(secret1).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("secret-ski.json")
        assertThat(secret2).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("secret-pros.json")
    }

    @Test
    fun `Verify sharedprefix violation when different uppercaseSuffix configured`() {
        mockHerkimerDatabase(
            createResourceHerkimer("ski-postgres", mapOf("foo" to "bar", "bar" to "baz")),
            createResourceHerkimer(
                "fus-oracle",
                mapOf("foo" to "baz", "bar" to "foo"),
                kind = ResourceKind.OracleDatabaseInstance
            )
        )

        assertThat {
            generateResources(
                """{ 
                "credentials": {
                    "ski" : {
                        "prefix":"DATABASE_CONFIG",
                        "resourceKind": "PostgresDatabaseInstance",
                        "multiple" : true
                    },
                    "fus" : {
                        "prefix":"DATABASE_CONFIG",
                        "resourceKind": "OracleDatabaseInstance",
                        "multiple" : true,
                        "uppercaseEnvVarsSuffix": false
                    }
                }
           }""",
                resources = mutableSetOf(createEmptyDeploymentConfig()),
                createdResources = 2
            )
        }.singleApplicationError("The shared prefix=DATABASE_CONFIG has been configured with both uppercaseEnvVarsSuffix=false and uppercaseEnvVarsSuffix=true.")
    }

    @Test
    fun `verify creating secret from herkimer with shared prefix`() {

        mockHerkimerDatabase(
            createResourceHerkimer("ski-postgres", mapOf("foo" to "bar", "bar" to "baz")),
            createResourceHerkimer(
                "fus-oracle",
                mapOf("foo" to "baz", "bar" to "foo"),
                kind = ResourceKind.OracleDatabaseInstance
            )
        )

        val (dc, secret1, secret2) = generateResources(
            """{ 
                "credentials": {
                    "ski" : {
                        "prefix":"DATABASE_CONFIG",
                        "resourceKind": "PostgresDatabaseInstance",
                        "multiple" : true,
                        "uppercaseEnvVarsSuffix": false
                    },
                    "fus" : {
                        "prefix":"DATABASE_CONFIG",
                        "resourceKind": "OracleDatabaseInstance",
                        "multiple" : true,
                        "uppercaseEnvVarsSuffix": false
                    }
                }
           }""",
            createdResources = 2,
            resources = mutableSetOf(createEmptyDeploymentConfig())
        )
        assertThat(dc).auroraResourceMatchesFile("dc-sharedprefix-multiple.json")
        assertThat(secret1).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("secret-ski.json")
        assertThat(secret2).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("secret-fus.json")
    }

    @Test
    fun `verify creating secret from herkimer for single response`() {

        mockHerkimerDatabase(
            createResourceHerkimer("ski-postgres", mapOf("foo" to "bar", "bar" to "baz"))
        )

        val (dc, secret) = generateResources(
            """{ 
                "credentials": {
                    "ski" : {
                        "resourceKind": "PostgresDatabaseInstance"
                    }
                }
           }""",
            createdResources = 1,
            resources = mutableSetOf(createEmptyDeploymentConfig())
        )
        assertThat(dc).auroraResourceMatchesFile("dc-single.json")
        assertThat(secret).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("secret-ski.json")
    }

    private fun mockHerkimerDatabase(
        vararg resourceHerkimer: ResourceHerkimer
    ) {
        resourceHerkimer.groupBy { it.kind }.forEach { (kind, groupedResourceHerkimer) ->
            every {
                herkimerService.getClaimedResources("1234567890", kind)
            } returns groupedResourceHerkimer
        }
    }

    private fun createResourceHerkimer(
        name: String,
        data: Map<String, String>,
        kind: ResourceKind = ResourceKind.PostgresDatabaseInstance
    ) = ResourceHerkimer(
        id = "0",
        name = name,
        kind = kind,
        ownerId = "1234567890",
        claims = listOf(createResourceClaim(data = jacksonObjectMapper().convertValue(data))),
        createdDate = LocalDateTime.now(),
        modifiedDate = LocalDateTime.now(),
        createdBy = "aurora",
        modifiedBy = "aurora",
        parentId = null
    )

    private fun createResourceClaim(
        data: JsonNode = jacksonObjectMapper().createObjectNode()
    ) =
        ResourceClaimHerkimer(
            id = "0L",
            ownerId = "owner",
            resourceId = 0L,
            credentials = data,
            createdDate = LocalDateTime.now(),
            modifiedDate = LocalDateTime.now(),
            createdBy = "aurora",
            modifiedBy = "aurora",
            name = "ADMIN"
        )
}
