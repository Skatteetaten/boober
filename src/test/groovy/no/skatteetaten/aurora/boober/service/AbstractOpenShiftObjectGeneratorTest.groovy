package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef.aid

import java.time.Instant

import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.model.AbstractAuroraDeploymentSpecTest
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.utils.Instants

class AbstractOpenShiftObjectGeneratorTest extends AbstractAuroraDeploymentSpecTest {

  public static final String DEPLOY_ID = '123'

  def userDetailsProvider = Mock(UserDetailsProvider)

  def openShiftResourceClient = Mock(OpenShiftResourceClient)

  def mapper = new ObjectMapper()

  OpenShiftObjectGenerator createObjectGenerator(String username = "aurora") {

    Instants.determineNow = {Instant.EPOCH }
    userDetailsProvider.getAuthenticatedUser() >> new User(username, "token", "Aurora OpenShift", [])
    def templateProcessor = new OpenShiftTemplateProcessor(userDetailsProvider, openShiftResourceClient, mapper)

    new OpenShiftObjectGenerator(
        "docker-registry.aurora.sits.no:5000",
        new OpenShiftObjectLabelService(userDetailsProvider),
        mapper,
        templateProcessor, openShiftResourceClient, ".utv.paas.skead.no")
  }

  def specJavaWithToxiproxy() {
    return createDeploymentSpec([
        "about.json"        : DEFAULT_ABOUT,
        "utv/about.json"    : DEFAULT_UTV_ABOUT,
        "reference.json"    : REF_APP_JSON,
        "utv/reference.json": '''{ "toxiproxy" : { "version" : "2.1.3" } }'''
    ], aid("utv", "reference"))
  }

  def specWebWithToxiproxy() {
    return createDeploymentSpec([
        "about.json"           : DEFAULT_ABOUT,
        "utv/about.json"       : DEFAULT_UTV_ABOUT,
        "webleveranse.json"    : WEB_LEVERANSE,
        "utv/webleveranse.json": '''{ "type": "deploy", "version" : "1.0.8", "database" : { "REFerence" : "auto" }, "toxiproxy" : { "version" : "2.1.3" } }'''
    ], aid("utv", "webleveranse"))
  }
}
