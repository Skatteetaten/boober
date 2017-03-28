package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.LoggingUtilsKt.setLogLevels

import no.skatteetaten.aurora.boober.ValidationHelper
import no.skatteetaten.aurora.boober.model.Result
import spock.lang.Specification

class ValidationTest extends Specification {

  def setupSpec() {
    setLogLevels()
  }

  def openshiftService = Mock(OpenShiftService)
  def service = new ValidationService(openshiftService)
  def token = "foobar"

  def helper = new ValidationHelper()

  def "should not run validation if config is empty "() {

    given:

      def res = new Result(null, [:], ["Files are missing"], [:], [:])

    when:
      def validationResult = service.assertIsValid(res, token)

    then:
      validationResult.errors.contains("Files are missing")
      validationResult.errors.size() == 1
  }

  def "should validate valid config"() {

    given:
      def res = helper.validMinimalConfig()

    when:
      def validationResult = service.assertIsValid(res, token)

    then:
      validationResult.valid
  }

  def "should fail if name is not set"() {

    given:
      def res = helper.configWithNameAndBuildArtifactIdMissing()
    when:
      def validationResult = service.assertIsValid(res, token)

    then:
      validationResult.errors.contains("size must be between 1 and 50 for field build.artifactId")
  }

  def "should fail if affiliation contains special character"() {

    given:
      def res = helper.affiliationWithSpecialChar()

    when:
      def validationResult = service.assertIsValid(res, token)

    then:
      validationResult.errors.contains("Only lowercase letters, max 24 length for field affiliation")
      validationResult.errors.contains("Alphanumeric and dashes. Cannot end or start with dash for field namespace")

      validationResult.errors.size() == 2
  }

  def "should fail if process type with invalid templateFile"() {

    given:
      def res = helper.processWithTemplateFile("template/deploy-amq.json")

    when:
      def validationResult = service.assertIsValid(res, token)

    then:
      validationResult.errors.contains("Template file template/deploy-amq.json is missing in sources")
  }

  def "should fail if process type with invalid template"() {

    given:
      def res = helper.processWithTemplate("foo")
      openshiftService.templateExist(token, "foo") >> false

    when:
      def validationResult = service.assertIsValid(res, token)


    then:
      validationResult.errors.contains("Template foo does not exist in cluster.")
  }

  def "should fail if process type with both template and templateFile directive"() {

    given:
      def res = helper.processWithTemplateAndTemplateFile("foo", "template/deploy-amq.json")

      openshiftService.templateExist(token, "foo") >> false

    when:
      def validationResult = service.assertIsValid(res, token)

    then:
      validationResult.errors.contains("Template foo does not exist in cluster.")
      validationResult.errors.contains("Template file template/deploy-amq.json is missing in sources")
      validationResult.errors.contains("Cannot specify both template and templateFile")
      validationResult.errors.size() == 3
  }
}
