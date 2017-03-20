package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.Config
import no.skatteetaten.aurora.boober.model.ConfigBuild
import no.skatteetaten.aurora.boober.model.ConfigDeploy
import no.skatteetaten.aurora.boober.model.Result
import no.skatteetaten.aurora.boober.model.TemplateType
import spock.lang.Specification

class ValidationTest extends Specification {

  def openshiftService = Mock(OpenshiftService)
  def service = new ValidationService(openshiftService)
  def token = "foobar"

  def "should not run validation if config is empty "() {

    given:

      def res = new Result(null, [:], ["Files are missing"])

    when:
      def validationResult = service.validate(res, token)

    then:
      validationResult.errors.contains("Files are missing")
      validationResult.errors.size() == 1
  }

  def "should validate valid config"() {

    given:
      def configBuild = new ConfigBuild("", "", "")
      def configDeploy = new ConfigDeploy("", "", "", "", "", 250, "", "", "", "", false, 8081, "/prometheus", "",
          false)
      def config = new Config("oas", "", "", "utv", TemplateType.deploy, 1, [], configBuild, "test", configDeploy, [:],
          "",
          "", "", [:], "")

      def res = new Result(config, [:], [])

    when:
      def validationResult = service.validate(res, token)

    then:
      validationResult.valid
  }

  def "should fail if name is not set"() {

    given:
      def configBuild = new ConfigBuild("", "", "")
      def configDeploy = new ConfigDeploy("", "", "", "", "", 250, "", "", "", "", false, 8081, "/prometheus", "",
          false)
      def config = new Config("oas", "", "", "utv", TemplateType.deploy, 1, [], configBuild, "", configDeploy, [:], "",
          "", "", [:], "")

      def res = new Result(config, [:], [])

    when:
      def validationResult = service.validate(res, token)

    then:
      validationResult.errors.contains("Name is not a valid DNS952 label ^[a-z][-a-z0-9]{0,23}[a-z0-9]\$")
  }

  def "should fail if affiliation contains special character"() {

    given:
      def configBuild = new ConfigBuild("", "", "")
      def configDeploy = new ConfigDeploy("", "", "", "", "", 250, "", "", "", "", false, 8081, "/prometheus", "",
          false)
      def config = new Config("yoo!", "", "", "utv", TemplateType.deploy, 1, [], configBuild, "foo", configDeploy, [:],
          "",
          "", "", [:], "")

      def res = new Result(config, [:], [])

    when:
      def validationResult = service.validate(res, token)

    then:
      validationResult.errors.contains("Affiliation is not valid ^[a-z]{0,23}[a-z]\$")
  }

  def "should fail if namespace contains special character"() {

    given:
      def configBuild = new ConfigBuild("", "", "")
      def configDeploy = new ConfigDeploy("", "", "", "", "", 250, "", "", "", "", false, 8081, "/prometheus", "",
          false)
      def config = new Config("yoo", "", "", "utv", TemplateType.deploy, 1, [], configBuild, "foo", configDeploy, [:],
          "",
          "", "", [:], "!yoo")

      def res = new Result(config, [:], [])

    when:
      def validationResult = service.validate(res, token)

    then:
      validationResult.errors.contains("Namespace is not valid ^[a-z0-9][-a-z0-9]*[a-z0-9]\$")
  }

  def "should fail if process type with invalid templateFile"() {

    given:
      def configBuild = new ConfigBuild("", "", "")

      def config = new Config("yoo", "", "", "utv", TemplateType.process, 1, [], configBuild, "foo", null, [:],
          "",
          "template/deploy-amq.json", null, [:], "")

      def res = new Result(config, [:], [])

    when:
      def validationResult = service.validate(res, token)

    then:
      validationResult.errors.contains("Template file template/deploy-amq.json is missing in sources")
  }

  def "should fail if process type with invalid template"() {

    given:
      def configBuild = new ConfigBuild("", "", "")

      def config = new Config("yoo", "", "", "utv", TemplateType.process, 1, [], configBuild, "foo", null, [:],
          "",
          null, "foo", [:], "")

      def res = new Result(config, [:], [])
      openshiftService.templateExist(token, "foo") >> false

    when:
      def validationResult = service.validate(res, token)


    then:
      validationResult.errors.contains("Template foo does not exist in cluster.")
  }


  def "should fail if process type with both template and templateFile directive"() {

    given:
      def configBuild = new ConfigBuild("", "", "")

      def config = new Config("yoo", "", "", "utv", TemplateType.process, 1, [], configBuild, "foo", null, [:],
          "",
          "template/deploy-amq.json", "foo", [:], "")

      def res = new Result(config, [:], [])
      openshiftService.templateExist(token, "foo") >> false

    when:
      def validationResult = service.validate(res, token)

    then:
      validationResult.errors.contains("Template foo does not exist in cluster.")
      validationResult.errors.contains("Template file template/deploy-amq.json is missing in sources")
      validationResult.errors.contains("Cannot specify both template and templateFile")
      validationResult.errors.size() == 3
  }

  def "should fail if process type with deploy block"() {

    given:
      def configBuild = new ConfigBuild("", "", "")
      def configDeploy = new ConfigDeploy("", "", "", "", "", 250, "", "", "", "", false, 8081, "/prometheus", "",
          false)
      def config = new Config("yoo", "", "", "utv", TemplateType.process, 1, [], configBuild, "foo", configDeploy, [:],
          "",
          null, "foo", [:], "")

      def res = new Result(config, [:], [])
      openshiftService.templateExist(token, "foo") >> true

    when:
      def validationResult = service.validate(res, token)

    then:
      validationResult.errors.contains("Deploy parameters are not viable for process type")

  }
}
