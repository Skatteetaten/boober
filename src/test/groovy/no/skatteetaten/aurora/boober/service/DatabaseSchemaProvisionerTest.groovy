package no.skatteetaten.aurora.boober.service

import spock.lang.Specification

class DatabaseSchemaProvisionerTest extends Specification {

  def a() {
    given:
      def provisioner = new DatabaseSchemaProvisioner()

    when:
      def provisionResult = provisioner.provisionSchemas([])

    then:
      provisionResult.success
  }
}
