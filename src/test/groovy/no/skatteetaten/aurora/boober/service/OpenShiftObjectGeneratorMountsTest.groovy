package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.model.ApplicationId.aid

import groovy.json.JsonSlurper
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec

class OpenShiftObjectGeneratorMountsTest extends AbstractOpenShiftObjectGeneratorTest {

  OpenShiftObjectGenerator objectGenerator = createObjectGenerator()

  def jsonSlurper = new JsonSlurper()

  def "A"() {

    given:
      def auroraConfigJson = [
          "about.json"         : DEFAULT_ABOUT,
          "utv/about.json"     : DEFAULT_UTV_ABOUT,
          "aos-simple.json"    : AOS_SIMPLE_JSON,
          "utv/aos-simple.json": '''{ "secretVault": "foo" }'''
      ]

      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, aid("utv", "aos-simple"))

    when:
      def mounts = objectGenerator.generateMounts(deploymentSpec, DEPLOY_ID)
      mounts = mounts.collect { jsonSlurper.parseText(it.toString()) }

    then:
      true
      println mounts
  }

  def "B"() {

    given:
      def auroraConfigJson = [
          "about.json"         : DEFAULT_ABOUT,
          "utv/about.json"     : DEFAULT_UTV_ABOUT,
          "aos-simple.json"    : AOS_SIMPLE_JSON,
          "utv/aos-simple.json": '''{ "config": {
    "latest": {
      "foo": "baaaar"
    }
  }'''
      ]

      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, aid("utv", "aos-simple"))

    when:
      def mounts = objectGenerator.generateMounts(deploymentSpec, DEPLOY_ID)
      mounts = mounts.collect { jsonSlurper.parseText(it.toString()) }

    then:
      true
      println mounts
  }

  def "C"() {

    given:
      def auroraConfigJson = modify(defaultAuroraConfig(), "utv/aos-simple.json", {
        mounts = [
            ("secret-mount"): [
                type       : "Secret",
                path       : "/u01/foo",
                secretVault: "foo"
            ]
        ]
      })

      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    when:
      def mounts = objectGenerator.generateMounts(deploymentSpec, DEPLOY_ID)
          .collect { jsonSlurper.parseText(it.toString()) }

    then:
      true
      println mounts
  }
}
