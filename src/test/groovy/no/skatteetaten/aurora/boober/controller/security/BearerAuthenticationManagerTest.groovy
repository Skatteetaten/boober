package no.skatteetaten.aurora.boober.controller.security

import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftGroups
import spock.lang.Specification

class BearerAuthenticationManagerTest extends Specification {

  def USERNAME = "aurora"
  def GROUPS = ["APP_PaaS_drift", "APP_PaaS_utv"]

  def "Gets authorities from OpenShift groups"() {

    given:
      def objectMapper = new ObjectMapper()
      def openShiftClient = Mock(OpenShiftClient)
      openShiftClient.findCurrentUser('some_token') >> objectMapper.
          readValue("""{"kind": "user", "metadata": {"name": "$USERNAME"}, "fullName": "Aurora Test User"}""", JsonNode)
      def GROUPS = ["APP_PaaS_drift", "APP_PaaS_utv"]
      openShiftClient.getGroups() >> new OpenShiftGroups(["aurora": GROUPS], [:])

      def authenticationManager = new BearerAuthenticationManager(openShiftClient)

    when:
      Authentication authentication = authenticationManager.authenticate(new TestingAuthenticationToken("Bearer some_token", ""))

    then:
      authentication.authenticated
      authentication.authorities.containsAll(GROUPS.collect { new SimpleGrantedAuthority(it) })
  }
}
