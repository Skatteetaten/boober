package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.Config
import no.skatteetaten.aurora.boober.model.ConfigBuild
import no.skatteetaten.aurora.boober.model.ConfigDeploy
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.OpenShiftConfigService
import spock.lang.Specification

class OpenShiftConfigServiceTest extends Specification {

  def "A"() {

    given:
      def configBuild = new ConfigBuild("", "", "")
      def configDeploy = new ConfigDeploy("", "", "", "", "", 250, "", "", "", "", false, 8081, "/prometheus", "",
          false)
      def config = new Config("oas", "", "", "utv", TemplateType.deploy, 1, [], configBuild, "", configDeploy, [:], "",
          "", "", [:], "")

      def configService = new OpenShiftConfigService()

    when:
      configService.applyConfig(config)

    then:
      true
  }
}
