package no.skatteetaten.aurora.boober.model

import static no.skatteetaten.aurora.boober.mapper.v1.DatabasePermission.READ
import static no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef.aid

import no.skatteetaten.aurora.boober.mapper.AuroraConfigException
import no.skatteetaten.aurora.boober.mapper.v1.DatabaseFlavor

class AuroraDeploymentSpecBuilderTest extends AbstractAuroraDeploymentSpecTest {

  def auroraConfigJson = defaultAuroraConfig()

  def defaultDatabaseInstance = new DatabaseInstance(null, true, [affiliation: "aos"])

  def "fileName can be long if both artifactId and name exist"() {
    given:
      auroraConfigJson["this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] = '''{ "type" : "deploy", "groupId" : "foo", "version": "1"}'''
      auroraConfigJson["utv/this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] = '''{ "name" : "foo", "artifactId" : "foo"}'''

    when:
      createDeploymentSpec(auroraConfigJson, aid("utv", "this-name-is-stupid-stupid-stupidly-long-for-no-reason"))

    then:
      notThrown(AuroraConfigException)
  }

  def "Fails when application name is too long and artifactId blank"() {
    given:
      auroraConfigJson["this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] = '''{ "type" : "deploy", "groupId" : "foo", "version": "1"}'''
      auroraConfigJson["utv/this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] = '''{ "name" : "foo"}'''

    when:
      createDeploymentSpec(auroraConfigJson, aid("utv", "this-name-is-stupid-stupid-stupidly-long-for-no-reason"))

    then:
      thrown(AuroraConfigException)
  }

  def "Should allow AuroraConfig for ApplicationIf with no name or artifactId"() {
    given:
      auroraConfigJson["reference.json"] = REFERENCE
      auroraConfigJson["utv/reference.json"] = '''{}'''

    when:
      def spec = createDeploymentSpec(auroraConfigJson, aid("utv", "reference"))

      spec
    then:
      notThrown(AuroraConfigException)
  }

  def "Fails when application name is too long and artifactId and name is blank"() {
    given:
      auroraConfigJson["this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] = '''{ "type" : "deploy", "groupId" : "foo", "version": "1"}'''
      auroraConfigJson["utv/this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] = '''{}'''

    when:
      createDeploymentSpec(auroraConfigJson, aid("utv", "this-name-is-stupid-stupid-stupidly-long-for-no-reason"))

    then:
      thrown(AuroraConfigException)
  }

  def "Fails when envFile does not start with about"() {
    given:
      auroraConfigJson["utv/foo.json"] = '''{ }'''
      auroraConfigJson["utv/aos-simple.json"] = '''{ "envFile": "foo.json" }'''

    when:
      createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    then:
      thrown(AuroraConfigException)
  }

  def "Disabling certificate with simplified config over full config"() {
    given:
      modify(auroraConfigJson, "aos-simple.json", {
        put("certificate", [commonName: "some_common_name"])
      })

    when:
      def deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
    then:
      deploymentSpec.integration.certificate == "some_common_name"

    when:
      modify(auroraConfigJson, "utv/aos-simple.json", {
        put("certificate", false)
      })
      deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
    then:
      !deploymentSpec.integration.certificate
  }

  def "Verify that it is possible to set some common global config options for templates even if they are not directly supported by that type"() {
    given:

      // certificate, splunkIndex and config are not directly supported by type=template
      auroraConfigJson = [
          "about.json"         : '''{
  "schemaVersion": "v1",
  "permissions": {
    "admin": "APP_PaaS_utv"
  },
  "affiliation" : "aos",
  
  "certificate": { 
    "commonName":"test"
  },
  "splunkIndex": "test",
  "database": true,
  "config": {
    "A_STANDARD_CONFIG": "a default value for all applications"
  }
}''',
          "utv/about.json"     : DEFAULT_UTV_ABOUT,
          "aos-simple.json"    : AOS_SIMPLE_JSON,
          "atomhopper.json"    : ATOMHOPPER,
          "utv/aos-simple.json": '''{ }''',
          "utv/atomhopper.json": '''{ }'''
      ]

      modify(auroraConfigJson, "aos-simple.json", {
        delegate.remove("certificate")
      })

    when:
      createDeploymentSpec(auroraConfigJson, aid("utv", "atomhopper"))
    then:
      noExceptionThrown()
  }

  def "Should fail when name is not valid DNS952 label"() {

    given:
      def aid = DEFAULT_AID
      modify(auroraConfigJson, "${aid.environment}/${aid.application}.json", {
        put("name", "test%qwe)")
      })
    when:
      def deploymentSpec = createDeploymentSpec(auroraConfigJson, aid)

    then:
      def ex = thrown(AuroraConfigException)
      ex.errors[0].field.path == 'name'
  }

  def "Should throw AuroraConfigException due to wrong version"() {

    given:
      def aid = DEFAULT_AID
      modify(auroraConfigJson, "${aid.application}.json", {
        put("version", "foo/bar")
      })
    when:
      createDeploymentSpec(auroraConfigJson, aid)

    then:
      def ex = thrown(AuroraConfigException)
      ex.errors[0].message == "Version must be a 128 characters or less, alphanumeric and can contain dots and dashes"
  }

  def "Should throw AuroraConfigException due to missing required properties"() {

    given:
      def aid = DEFAULT_AID
      modify(auroraConfigJson, "${aid.application}.json", {
        remove("version")
      })
    when:
      createDeploymentSpec(auroraConfigJson, aid)

    then:
      def ex = thrown(AuroraConfigException)
      ex.errors[0].message == "Version must be a 128 characters or less, alphanumeric and can contain dots and dashes"
  }

  def "Fails when affiliation is not in about file"() {
    given:
      auroraConfigJson["utv/aos-simple.json"] = '''{ "affiliation": "aaregistere" }'''

    when:
      createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    then:
      def e = thrown(AuroraConfigException)
      e.message ==
          "Config for application aos-simple in environment utv contains errors. Invalid Source field=affiliation requires an about source. Actual source is source=utv/aos-simple.json."
  }

  def "Fails when affiliation is too long"() {
    given:
      auroraConfigJson["utv/about.json"] = '''{ "affiliation": "aaregistere", "cluster" : "utv" }'''

    when:
      createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    then:
      def e = thrown(AuroraConfigException)
      e.message ==
          "Config for application aos-simple in environment utv contains errors. Affiliation can only contain letters and must be no longer than 10 characters."
  }

  def "Parses variants of secretVault config correctly"() {
    given:
      auroraConfigJson["utv/aos-simple.json"] = configFile
      AuroraDeploymentSpecInternal deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    expect:
      deploymentSpec.getVolume().secretVaultName == vaultName
      deploymentSpec.getVolume().secretVaultKeys == keys

    where:
      configFile                                                                                         | vaultName   |
          keys
      '''{ "secretVault": "vaultName" }'''                                                               | "vaultName" |
          []
      '''{ "secretVault": {"name": "test"} }'''                                                          | "test"      |
          []
      '''{ "secretVault": {"name": "test", "keys": []} }'''                                              | "test"      |
          []
      '''{ "secretVault": {"name": "test", "keys": ["test1", "test2"]} }'''                              | "test"      |
          ["test1", "test2"]
      '''{ "secretVault": {"name": "test", "keys": ["test1"], "keyMappings":{"test1":"newtestkey"}} }''' | "test"      |
          ["test1"]
  }

  def "Permissions supports both space separated string and array"() {
    given:
      modify(auroraConfigJson, "about.json", {
        put("permissions", ["admin": adminPermissions])
      })

    when:
      AuroraDeploymentSpecInternal deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    then:
      def adminGroups = deploymentSpec.environment.permissions.admin.groups
      adminGroups == ["APP_PaaS_utv", "APP_PaaS_drift"] as Set

    where:
      adminPermissions << ["APP_PaaS_utv APP_PaaS_drift", ["APP_PaaS_utv", "APP_PaaS_drift"]]
  }

  def "Webseal roles supports both comma separated string and array"() {
    given:
      modify(auroraConfigJson, "utv/aos-simple.json") {
        put("webseal", ["roles": roleConfig])
      }

    when:
      AuroraDeploymentSpecInternal deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    then:
      def roles = deploymentSpec.integration.webseal.roles
      roles == "role1,role2,3"

    where:
      roleConfig << ["role1,role2,3", "role1, role2, 3", ["role1", "role2", 3]]
  }

  def "Fails when annotation has wrong separator"() {
    given:
      def auroraConfigJson = defaultAuroraConfig()
      auroraConfigJson["utv/aos-simple.json"] = '''{
  "route": {
    "console": {
      "annotations": {
        "haproxy.router.openshift.io/timeout": "600s"
      }
    }
  }
}
'''

    when:
      createDeploymentSpec(auroraConfigJson, aid("utv", "aos-simple"))

    then:
      def e = thrown AuroraConfigException
      e.message == '''Config for application aos-simple in environment utv contains errors. Annotation haproxy.router.openshift.io/timeout cannot contain '/'. Use '|' instead.'''
  }

  def "Should use overridden db name when set to default at higher level"() {

    given:
      def aid = DEFAULT_AID
      modify(auroraConfigJson, "about.json", {
        put("database", true)
      })
      modify(auroraConfigJson, "utv/aos-simple.json", {
        put("database", ["foobar": "auto"])
      })
    when:
      def deploymentSpec = createDeploymentSpec(auroraConfigJson, aid)

    then:
      deploymentSpec.integration.database ==
          [new Database("foobar", null, DatabaseFlavor.ORACLE_MANAGED, true, [:], [:], defaultDatabaseInstance)]
  }

  def "Should use databaseDefaults"() {
    given:
      def aid = DEFAULT_AID
      modify(auroraConfigJson, "about.json", {
        put("databaseDefaults", [
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
    when:
      def deploymentSpec = createDeploymentSpec(auroraConfigJson, aid)
    then:
      def instance = new DatabaseInstance("corrusant", true, [type: "ytelse", affiliation: "aos"])
      deploymentSpec.integration.database ==
          [new Database("ohyeah", null, DatabaseFlavor.POSTGRES_MANAGED, false, [foobar: "jalla"], [jalla: READ],
              instance)]
  }

  def "Should use expanded database configuration"() {
    given:
      def aid = DEFAULT_AID
      modify(auroraConfigJson, "about.json", {
        put("databaseDefaults", [
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
        put("database", [
            foo: [
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
    when:
      def deploymentSpec = createDeploymentSpec(auroraConfigJson, aid)

    then:
      deploymentSpec.integration.database ==
          [new Database("foo", "123", DatabaseFlavor.POSTGRES_MANAGED, false,
              [foobar: "read"],
              [jalla: READ, read: READ],
              new DatabaseInstance(null, false, [foo: "bar", baz: "bar", affiliation: "aos"]))]
  }

  def "Should use overridden cert name when set to default at higher level"() {

    given:
      def aid = DEFAULT_AID
      modify(auroraConfigJson, "about.json", {
        put("certificate", true)
      })
      modify(auroraConfigJson, "utv/aos-simple.json", {
        put("certificate", ["commonName": "foooo"])
      })
    when:
      def deploymentSpec = createDeploymentSpec(auroraConfigJson, aid)

    then:
      deploymentSpec.integration.certificate == "foooo"
  }

  def "Should use overridden cert name when explicitly disabled at higher level"() {

    given:
      def aid = DEFAULT_AID
      modify(auroraConfigJson, "aos-simple.json", {
        put("certificate", false)
      })
      modify(auroraConfigJson, "utv/aos-simple.json", {
        put("certificate", ["commonName": "foooo"])
      })
    when:
      def deploymentSpec = createDeploymentSpec(auroraConfigJson, aid)

    then:
      deploymentSpec.integration.certificate == "foooo"
  }

  def "Should generate route with complex config"() {

    given:
      def aid = DEFAULT_AID

      modify(auroraConfigJson, "utv/aos-simple.json", {
        put("route", [foo: [host: "host"]])
      })
    when:
      def deploymentSpec = createDeploymentSpec(auroraConfigJson, aid)

    then:
      deploymentSpec.route.route[0].host == "host"
  }

  def "Should generate route with simple config"() {

    given:
      def aid = DEFAULT_AID

      modify(auroraConfigJson, "utv/aos-simple.json", {
        put("route", true)
      })
    when:
      def deploymentSpec = createDeploymentSpec(auroraConfigJson, aid)

    then:
      deploymentSpec.route.route[0].host == "aos-simple-aos-utv"
  }

  def "Should use base file name as default artifactId"() {
    given:
      auroraConfigJson["utv/reference.json"] = '''{ "baseFile" : "aos-simple.json"}'''

    when:
      def spec = createDeploymentSpec(auroraConfigJson, aid("utv", "reference"))

    then:
      spec.deploy.artifactId == "aos-simple"
  }
}
