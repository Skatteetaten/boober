package no.skatteetaten.aurora.boober.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.catch
import no.skatteetaten.aurora.boober.mapper.AuroraConfigException
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef.Companion.aid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class AuroraDeploymentSpecBuilderTest : AbstractAuroraConfigTest2() {

    lateinit var auroraConfigJson: MutableMap<String, String>

    @BeforeEach
    fun setup() {
        auroraConfigJson = defaultAuroraConfig()
    }

    val defaultDatabaseInstance = DatabaseInstance(fallback = true, labels = mapOf("affiliation" to "aos"))

    @Test
    fun `fileName can be long if both artifactId and name exist`() {

        auroraConfigJson["this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] =
            """{ "type" : "deploy", "groupId" : "foo", "version": "1"}"""
        auroraConfigJson["utv/this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] =
            """{ "name" : "foo", "artifactId" : "foo"}"""

        assertThat {
            createDeploymentSpec(auroraConfigJson, aid("utv", "this-name-is-stupid-stupid-stupidly-long-for-no-reason"))
        }.doesNotThrowAnyException()
    }

    @Test
    fun `Fails when application name is too long and artifactId blank`() {

        auroraConfigJson["this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] =
            """{ "type" : "deploy", "groupId" : "foo", "version": "1"}"""
        auroraConfigJson["utv/this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] = """{ "name" : "foo"}"""


        assertThat {
            createDeploymentSpec(auroraConfigJson, aid("utv", "this-name-is-stupid-stupid-stupidly-long-for-no-reason"))
        }.thrownError {
            isInstanceOf(AuroraConfigException::class)
        }
    }

    @Test
    fun `Should allow AuroraConfig for ApplicationIf with no name or artifactId`() {

        auroraConfigJson["reference.json"] = REFERENCE
        auroraConfigJson["utv/reference.json"] = """{}"""


        assertThat {
            createDeploymentSpec(auroraConfigJson, aid("utv", "reference"))
        }.doesNotThrowAnyException()
    }

    @Test
    fun `Fails when application name is too long and artifactId and name is blank`() {

        auroraConfigJson["this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] =
            """{ "type" : "deploy", "groupId" : "foo", "version": "1"}"""
        auroraConfigJson["utv/this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] = """{}"""


        assertThat {
            createDeploymentSpec(auroraConfigJson, aid("utv", "this-name-is-stupid-stupid-stupidly-long-for-no-reason"))
        }.thrownError {
            isInstanceOf(AuroraConfigException::class)
        }
    }

    @Test
    fun `Fails when envFile does not start with about`() {

        auroraConfigJson["utv/foo.json"] = """{ }"""
        auroraConfigJson["utv/aos-simple.json"] = """{ "envFile": "foo.json" }"""

        assertThat {
            createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
        }.thrownError {
            isInstanceOf(AuroraConfigException::class)
        }
    }

    @Test
    fun `Disabling certificate with simplified config over full config`() {

        modify(auroraConfigJson, "aos-simple.json") {
            it["certificate"] = mapOf("commonName" to "some_common_name")
        }

        var spec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
        assertThat(spec.integration?.certificate).isEqualTo("some_common_name")

        modify(auroraConfigJson, "utv/aos-simple.json") {
            it["certificate"] = false
        }

        spec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
        assertThat(spec.integration?.certificate).isNull()
    }

    @Test
    fun `Should fail when name is not valid DNS952 label`() {

        modify(auroraConfigJson, "${DEFAULT_AID.environment}/${DEFAULT_AID.application}.json") {
            it["name"] = "test%qwe)"
        }

        val e: AuroraConfigException =
            catch { createDeploymentSpec(auroraConfigJson, DEFAULT_AID) } as AuroraConfigException
        assertThat(e).isNotNull()
        assertThat(e.errors[0].field?.path).isEqualTo("name")
    }

    @Test
    fun `Should throw AuroraConfigException due to wrong version`() {

        modify(auroraConfigJson, "${DEFAULT_AID.application}.json") {
            it["version"] = "foo/bar"
        }

        val e: AuroraConfigException =
            catch { createDeploymentSpec(auroraConfigJson, DEFAULT_AID) } as AuroraConfigException
        assertThat(e).isNotNull()
        assertThat(e.errors[0].message).isEqualTo("Version must be a 128 characters or less, alphanumeric and can contain dots and dashes")
    }

    @Test
    fun `Should throw AuroraConfigException due to missing required properties`() {

        modify(auroraConfigJson, "${DEFAULT_AID.application}.json") {
            it.remove("version")
        }

        val e: AuroraConfigException =
            catch { createDeploymentSpec(auroraConfigJson, DEFAULT_AID) } as AuroraConfigException
        assertThat(e).isNotNull()
        assertThat(e.errors[0].message).isEqualTo("Version must be a 128 characters or less, alphanumeric and can contain dots and dashes")
    }

    @Test
    fun `Fails when affiliation is not in about file`() {

        auroraConfigJson["utv/aos-simple.json"] = """{ "affiliation": "aaregistere" }"""

        val e: AuroraConfigException =
            catch { createDeploymentSpec(auroraConfigJson, DEFAULT_AID) } as AuroraConfigException

        assertThat(e).isNotNull()
        assertThat(e.message).isEqualTo("Config for application aos-simple in environment utv contains errors. Invalid Source field=affiliation requires an about source. Actual source is source=utv/aos-simple.json.")
    }

    @Test
    fun `Fails when affiliation is too long`() {

        auroraConfigJson["utv/about.json"] = """{ "affiliation": "aaregistere", "cluster" : "utv" }"""

        val e: AuroraConfigException =
            catch { createDeploymentSpec(auroraConfigJson, DEFAULT_AID) } as AuroraConfigException

        assertThat(e).isNotNull()
        assertThat(e.message).isEqualTo("Config for application aos-simple in environment utv contains errors. Affiliation can only contain letters and must be no longer than 10 characters.")
    }

    data class SecretVaultTestData(
        val configFile: String,
        val vaultName: String,
        val keys: List<String>
    )

    fun secretVaultTestData(): List<SecretVaultTestData> = listOf(

        SecretVaultTestData("""{ "secretVault": "vaultName" }""", "vaultName", emptyList()),
        SecretVaultTestData("""{ "secretVault": {"name": "test"} }""", "test", emptyList()),
        SecretVaultTestData("""{ "secretVault": {"name": "test", "keys": []} }""", "test", emptyList()),
        SecretVaultTestData(
            """{ "secretVault": {"name": "test", "keys": ["test1", "test2"]} }""",
            "test",
            listOf("test1", "test2")
        ),
        SecretVaultTestData(
            """{ "secretVault": {"name": "test", "keys": ["test1"], "keyMappings":{"test1":"newtestkey"}} }""",
            "test",
            listOf("test1")
        )

    )

    @ParameterizedTest
    @MethodSource("secretVaultTestData")
    @Test
    fun `Parses variants of secretVault config correctly`(testData: SecretVaultTestData) {

        auroraConfigJson["utv/aos-simple.json"] = testData.configFile

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        assertThat(deploymentSpec.volume?.secretVaultKeys).isEqualTo(testData.keys)
        assertThat(deploymentSpec.volume?.secretVaultName).isEqualTo(testData.vaultName)
    }

    fun permissionsTestData(): List<Any> = listOf(
        "APP_PaaS_utv APP_PaaS_drift",
        listOf("APP_PaaS_utv", "APP_PaaS_drift")
    )

    @ParameterizedTest
    @MethodSource("permissionsTestData")
    fun `Permissions supports both space separated string`(permissions: Any) {

        modify(auroraConfigJson, "about.json") {
            it["permissions"] = mapOf("admin" to permissions)
        }

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
        assertThat(deploymentSpec.environment.permissions.admin.groups).isEqualTo(
            setOf(
                "APP_PaaS_utv",
                "APP_PaaS_drift"
            )
        )
    }

    fun websealRolesTestData(): List<Any> = listOf(
        "role1,role2,3",
        "role1, role2, 3",
        listOf("role1", "role2", 3)
    )

    @ParameterizedTest
    @MethodSource("websealRolesTestData")
    fun `Webseal roles supports both comma separated string and array`(roleConfig: Any) {

        modify(auroraConfigJson, "utv/aos-simple.json") {
            it["webseal"] = mapOf("roles" to roleConfig)
        }

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        assertThat(deploymentSpec.integration?.webseal?.roles).isEqualTo("role1,role2,3")
    }

    /*
    fun `Fails when annotation has wrong separator`() {

        val auroraConfigJson = defaultAuroraConfig()
        auroraConfigJson["utv/aos-simple.json"] = """{
"route": {
    "console": {
    "annotations": {
    "haproxy.router.openshift.io/timeout": "600s"
}
}
}
}
"""


        createDeploymentSpec(auroraConfigJson, aid("utv", "aos-simple"))

        val e = thrown AuroraConfigException
            e.message == """Config for application aos-simple in environment utv contains errors. Annotation haproxy.router.openshift.io/timeout cannot contain "/". Use "|" instead."""
    }

    fun `Should use overridden db name when set to default at higher level`() {

        val aid = DEFAULT_AID
        modify(auroraConfigJson, "about.json", {
            put("database", true)
        })
        modify(auroraConfigJson, "utv/aos-simple.json", {
            put("database", ["foobar": "auto"])
        })

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, aid)


        deploymentSpec.integration.database ==
            [new Database ("foobar", null, DatabaseFlavor.ORACLE_MANAGED, true, [:], [:], defaultDatabaseInstance)]
    }

    fun `Should use databaseDefaults`() {

        val aid = DEFAULT_AID
        modify(auroraConfigJson, "about.json", {
            put(
                "databaseDefaults", [
                    "name"    : "ohyeah",
            "flavor"  : "POSTGRES_MANAGED",
            "generate": false,
            "roles"   : [
            "jalla": "READ"
            ],
            "exposeTo": [
            "foobar": "jalla"
            ],
            "instance": [
            "name"    : "corrusant",
            "fallback": true,
            "labels"  : [
            "type": "ytelse"
            ]
            ],
            ])
        })
        modify(auroraConfigJson, "utv/aos-simple.json", {
            put("database", true)
        })

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, aid)

        val instance = new DatabaseInstance ("corrusant", true, [type: "ytelse", affiliation: "aos"])
        deploymentSpec.integration.database ==
            [new Database ("ohyeah", null, DatabaseFlavor.POSTGRES_MANAGED, false, [foobar: "jalla"], [jalla: READ],
        instance)]
    }

    fun `Should use expanded database configuration`() {

        val aid = DEFAULT_AID
        modify(auroraConfigJson, "about.json", {
            put(
                "databaseDefaults", [
                    name    : "ohyeah",
            flavor  : "POSTGRES_MANAGED",
            generate: false,
            roles   : [
            jalla: "READ"
            ],
            exposeTo: [
            foobar: "jalla"
            ],
            instance: [
            labels: [
            foo: "bar"
            ]
            ]
            ])
        })
        modify(auroraConfigJson, "utv/aos-simple.json", {
            put(
                "database", [
                    foo:[
                id      : "123",
            roles   : [
            read: "READ"
            ],
            exposeTo: [
            foobar: "read"
            ],
            instance: [
            labels: [
            baz: "bar"
            ]
            ]
            ]
            ])
        })

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, aid)


        deploymentSpec.integration.database ==
            [new Database ("foo", "123", DatabaseFlavor.POSTGRES_MANAGED, false,
                [foobar: "read"],
        [jalla: READ, read: READ],
        new DatabaseInstance (null, false, [foo: "bar", baz: "bar", affiliation: "aos"]))]
    }

    fun `Should use overridden cert name when set to default at higher level`() {

        val aid = DEFAULT_AID
        modify(auroraConfigJson, "about.json", {
            put("certificate", true)
        })
        modify(auroraConfigJson, "utv/aos-simple.json", {
            put("certificate", ["commonName": "foooo"])
        })

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, aid)


        deploymentSpec.integration.certificate == "foooo"
    }

    fun `Should use overridden cert name when explicitly disabled at higher level`() {

        val aid = DEFAULT_AID
        modify(auroraConfigJson, "aos-simple.json", {
            put("certificate", false)
        })
        modify(auroraConfigJson, "utv/aos-simple.json", {
            put("certificate", ["commonName": "foooo"])
        })

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, aid)


        deploymentSpec.integration.certificate == "foooo"
    }

    fun `Should generate route with complex config`() {

        val aid = DEFAULT_AID

        modify(auroraConfigJson, "utv/aos-simple.json", {
            put("route", [foo:[host: "host"]])
        })

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, aid)


        deploymentSpec.route.route[0].host == "host"
    }

    fun `Should generate route with simple config`() {

        val aid = DEFAULT_AID

        modify(auroraConfigJson, "utv/aos-simple.json", {
            put("route", true)
        })

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, aid)


        deploymentSpec.route.route[0].host == "aos-simple-aos-utv"
    }

    fun `Should use base file name as default artifactId`() {

        auroraConfigJson["utv/reference.json"] = """{ "baseFile" : "aos-simple.json"}"""

        val spec = createDeploymentSpec(auroraConfigJson, aid("utv", "reference"))


        spec.deploy.artifactId == "aos-simple"
    }
    */
}
