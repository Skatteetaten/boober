package no.skatteetaten.aurora.boober.unit

import no.skatteetaten.aurora.boober.utils.AbstractAuroraConfigTest

class AuroraDeploymentSpecBuilderTest : AbstractAuroraConfigTest() {

    /*
    lateinit var auroraConfigJson: MutableMap<String, String>

    @BeforeEach
    fun setup() {
        auroraConfigJson = defaultAuroraConfig()
    }

    val defaultDatabaseInstance =
        DatabaseInstance(fallback = true, labels = mapOf("affiliation" to "aos"))

    @Test
    fun `fileName can be long if both artifactId and name exist`() {

        auroraConfigJson["this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] =
            """{ "type" : "deploy", "groupId" : "foo", "version": "1"}"""
        auroraConfigJson["utv/this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] =
            """{ "name" : "foo", "artifactId" : "foo"}"""

        assertThat {
            createDeploymentSpec(auroraConfigJson, adr("utv", "this-name-is-stupid-stupid-stupidly-long-for-no-reason"))
        }.isSuccess()
    }

    @Test
    fun `Fails when application name is too long and artifactId blank`() {

        auroraConfigJson["this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] =
            """{ "type" : "deploy", "groupId" : "foo", "version": "1"}"""
        auroraConfigJson["utv/this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] = """{ "name" : "foo"}"""

        assertThat {
            createDeploymentSpec(auroraConfigJson, adr("utv", "this-name-is-stupid-stupid-stupidly-long-for-no-reason"))
        }.isFailure().all {
            isInstanceOf(AuroraConfigException::class)
        }
    }

    @Test
    fun `Should allow AuroraConfig for ApplicationIf with no name or artifactId`() {

        auroraConfigJson["reference.json"] = REFERENCE
        auroraConfigJson["utv/reference.json"] = """{}"""

        assertThat {
            createDeploymentSpec(auroraConfigJson, adr("utv", "reference"))
        }.isSuccess()
    }

    @Test
    fun `Fails when application name is too long and artifactId and name is blank`() {

        auroraConfigJson["this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] =
            """{ "type" : "deploy", "groupId" : "foo", "version": "1"}"""
        auroraConfigJson["utv/this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] = """{}"""

        assertThat {
            createDeploymentSpec(auroraConfigJson, adr("utv", "this-name-is-stupid-stupid-stupidly-long-for-no-reason"))
        }.isFailure().all {
            isInstanceOf(AuroraConfigException::class)
        }
    }

    @Test
    fun `Fails when envFile does not start with about`() {

        auroraConfigJson["utv/foo.json"] = """{ }"""
        auroraConfigJson["utv/aos-simple.json"] = """{ "envFile": "foo.json" }"""

        assertThat {
            createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
        }.isFailure().all {
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

        assertThat {
            createDeploymentSpec(auroraConfigJson, DEFAULT_AID) as AuroraConfigException
        }.isNotNull().isFailure().isInstanceOf(AuroraConfigException::class)
            .messageContains("Name must be alphanumeric and no more than 40 characters.")
    }

    @Test
    fun `Should throw AuroraConfigException due to wrong version`() {

        modify(auroraConfigJson, "${DEFAULT_AID.application}.json") {
            it["version"] = "foo/bar"
        }

        assertThat {
            createDeploymentSpec(auroraConfigJson, DEFAULT_AID) as AuroraConfigException
        }.isNotNull().isFailure().isInstanceOf(AuroraConfigException::class)
            .messageContains("Version must be a 128 characters or less, alphanumeric and can contain dots and dashes")
    }

    @Test
    fun `Should throw AuroraConfigException due to missing required properties`() {

        modify(auroraConfigJson, "${DEFAULT_AID.application}.json") {
            it.remove("version")
        }

        assertThat {
            createDeploymentSpec(auroraConfigJson, DEFAULT_AID) as AuroraConfigException
        }.isNotNull().isFailure().isInstanceOf(AuroraConfigException::class)
            .messageContains("Version must be a 128 characters or less, alphanumeric and can contain dots and dashes")
    }

    @Test
    fun `Fails when affiliation is not in about file`() {

        auroraConfigJson["utv/aos-simple.json"] = """{ "affiliation": "aaregistere" }"""

        assertThat {
            createDeploymentSpec(auroraConfigJson, DEFAULT_AID) as AuroraConfigException
        }.isNotNull().isFailure().isInstanceOf(AuroraConfigException::class)
            .message()
            .isEqualTo("Config for application aos-simple in environment utv contains errors. Invalid Source field=affiliation requires an about source. Actual source is source=utv/aos-simple.json.")
    }

    @Test
    fun `Fails when affiliation is too long`() {

        auroraConfigJson["utv/about.json"] = """{ "affiliation": "aaregistere", "cluster" : "utv" }"""

        assertThat {
            createDeploymentSpec(auroraConfigJson, DEFAULT_AID) as AuroraConfigException
        }.isNotNull().isFailure().isInstanceOf(AuroraConfigException::class)
            .message()
            .isEqualTo("Config for application aos-simple in environment utv contains errors. Affiliation can only contain letters and must be no longer than 10 characters.")
    }

    enum class PermissionsTestData(val values: Any) {
        SINGLE_VALUE("APP_PaaS_utv APP_PaaS_drift"),
        LIST(listOf("APP_PaaS_utv", "APP_PaaS_drift"))
    }

    @ParameterizedTest
    @EnumSource(PermissionsTestData::class)
    fun `Permissions supports both space separated string`(permissions: PermissionsTestData) {

        modify(auroraConfigJson, "about.json") {
            it["permissions"] = mapOf("admin" to permissions.values)
        }

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
        assertThat(deploymentSpec.environment.permissions.admin.groups).isEqualTo(
            setOf(
                "APP_PaaS_utv",
                "APP_PaaS_drift"
            )
        )
    }

    enum class WebsealRolesTestData(val values: Any) {
        COMMA_SEPARATED("role1,role2,3"),
        COMMA_SEPARATED_WITH_SPACE("role1, role2, 3"),
        LIST(listOf("role1", "role2", 3))
    }

    @ParameterizedTest
    @EnumSource(WebsealRolesTestData::class)
    fun `Webseal roles supports both comma separated string and array`(roleConfig: WebsealRolesTestData) {

        modify(auroraConfigJson, "utv/aos-simple.json") {
            it["webseal"] = mapOf("roles" to roleConfig.values)
        }

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        assertThat(deploymentSpec.integration?.webseal?.roles).isEqualTo("role1,role2,3")
    }

    @Test
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

        assertThat {
            createDeploymentSpec(auroraConfigJson, DEFAULT_AID) as AuroraConfigException
        }.isNotNull().isFailure().isInstanceOf(AuroraConfigException::class)
            .message()
            .isEqualTo("""Config for application aos-simple in environment utv contains errors. Annotation haproxy.router.openshift.io/timeout cannot contain '/'. Use '|' instead.""")
    }

    @Test
    fun `Should use overridden db name when set to default at higher level`() {

        modify(auroraConfigJson, "about.json") {
            it["database"] = true
        }
        modify(auroraConfigJson, "utv/aos-simple.json") {
            it["database"] = mapOf("foobar" to "auto")
        }

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        assertThat(deploymentSpec.integration?.database).isEqualTo(
            listOf(
                Database(
                    name = "foobar",
                    flavor = DatabaseFlavor.ORACLE_MANAGED,
                    instance = defaultDatabaseInstance,
                    generate = true
                )
            )
        )
    }

    @Test
    fun `Should use databaseDefaults`() {

        modify(auroraConfigJson, "about.json") {
            it["databaseDefaults"] = mapOf(
                "name" to "ohyeah",
                "flavor" to "POSTGRES_MANAGED",
                "generate" to false,
                "roles" to mapOf("jalla" to "READ"),
                "exposeTo" to mapOf("foobar" to "jalla"),
                "instance" to mapOf("name" to "corrusant", "fallback" to true, "labels" to mapOf("type" to "ytelse"))
            )
        }
        modify(auroraConfigJson, "utv/aos-simple.json") {
            it["database"] = true
        }

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        val instance = DatabaseInstance(
            "corrusant",
            true,
            mapOf("type" to "ytelse", "affiliation" to "aos")
        )

        assertThat(deploymentSpec.integration?.database).isEqualTo(
            listOf(
                Database(
                    name = "ohyeah",
                    flavor = DatabaseFlavor.POSTGRES_MANAGED,
                    generate = false,
                    roles = mapOf("jalla" to READ),
                    exposeTo = mapOf("foobar" to "jalla"),
                    instance = instance
                )
            )
        )
    }

    @Test
    fun `Should use expanded database configuration`() {

        modify(auroraConfigJson, "about.json") {
            it["databaseDefaults"] = mapOf(
                "name" to "ohyeah",
                "flavor" to "POSTGRES_MANAGED",
                "generate" to false,
                "roles" to mapOf("jalla" to "READ"),
                "exposeTo" to mapOf("foobar" to "jalla"),
                "instance" to mapOf("labels" to mapOf("foo" to "bar"))
            )
        }

        modify(auroraConfigJson, "utv/aos-simple.json") {
            it["database"] = mapOf(
                "foo" to mapOf(
                    "id" to "123",
                    "roles" to mapOf("read" to "READ"),
                    "exposeTo" to mapOf("foobar" to "read"),
                    "instance" to mapOf("labels" to mapOf("bar" to "baz"))
                )
            )
        }

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        assertThat(deploymentSpec.integration?.database?.first()).isEqualTo(
            Database(
                name = "foo",
                id = "123",
                flavor = DatabaseFlavor.POSTGRES_MANAGED,
                generate = false,
                exposeTo = mapOf("foobar" to "read"),
                roles = mapOf("jalla" to READ, "read" to READ),
                instance = DatabaseInstance(
                    fallback = false, labels = mapOf("foo" to "bar", "bar" to "baz", "affiliation" to "aos")
                )

            )
        )
    }

    @Test
    fun `Should use overridden cert name when set to default at higher level`() {

        modify(auroraConfigJson, "about.json") {
            it["certificate"] = true
        }
        modify(auroraConfigJson, "utv/aos-simple.json") {
            it["certificate"] = mapOf("commonName" to "foooo")
        }

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        assertThat(deploymentSpec.integration?.certificate).isEqualTo("foooo")
    }

    @Test
    fun `Should use overridden cert name when explicitly disabled at higher level`() {

        val aid = DEFAULT_AID
        modify(auroraConfigJson, "aos-simple.json") {
            it["certificate"] = false
        }

        modify(auroraConfigJson, "utv/aos-simple.json") {
            it["certificate"] = mapOf("commonName" to "foooo")
        }

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, aid)

        assertThat(deploymentSpec.integration?.certificate).isEqualTo("foooo")
    }

    @Test
    fun `Should generate route with complex config`() {

        modify(auroraConfigJson, "utv/aos-simple.json") {
            it["route"] = mapOf("foo" to mapOf("host" to "host"))
        }

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        assertThat(deploymentSpec.route?.route?.get(0)?.host).isEqualTo("host")
    }

    @Test
    fun `Should generate route with simple config`() {

        modify(this.auroraConfigJson, "utv/aos-simple.json") {
            it["route"] = true
        }

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        assertThat(deploymentSpec.route?.route?.get(0)?.host).isEqualTo("aos-simple-aos-utv")
    }

    @Test
    fun `Should use base file name as default artifactId`() {

        auroraConfigJson["utv/reference.json"] = """{ "baseFile" : "aos-simple.json"}"""

        val spec = createDeploymentSpec(auroraConfigJson, adr("utv", "reference"))

        assertThat(spec.deploy?.artifactId).isEqualTo("aos-simple")
    }

     */
}
