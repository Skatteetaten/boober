package no.skatteetaten.aurora.boober.controller.security

import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftGroups
import no.skatteetaten.aurora.boober.service.openshift.UserGroup
import spock.lang.Specification

class BearerAuthenticationManagerTest extends Specification {

  static USERNAME = "aurora"
  static GROUPS = ["APP_PaaS_drift", "APP_PaaS_utv"]
  static TOKEN = 'some_token'

  def "Gets authorities from OpenShift groups"() {

    given:
      def objectMapper = new ObjectMapper()
      def openShiftClient = Mock(OpenShiftClient)
      openShiftClient.findCurrentUser(TOKEN) >> objectMapper.
          readValue("""{"kind": "user", "metadata": {"name": "$USERNAME"}, "fullName": "Aurora Test User"}""", JsonNode)


      openShiftClient.getGroups() >> new OpenShiftGroups([
          new UserGroup("aurora", "APP_PaaS_drift"),
          new UserGroup("aurora", "APP_PaaS_utv")])

      def authenticationManager = new BearerAuthenticationManager(openShiftClient)

    when:
      Authentication authentication = authenticationManager.authenticate(new TestingAuthenticationToken("Bearer $TOKEN", ""))

    then:
      !authentication.authenticated
      authentication.authorities.containsAll(GROUPS.collect { new SimpleGrantedAuthority(it) })
  }
}
