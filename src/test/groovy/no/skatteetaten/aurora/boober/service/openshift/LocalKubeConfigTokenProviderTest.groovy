package no.skatteetaten.aurora.boober.service.openshift

import no.skatteetaten.aurora.boober.service.AbstractSpec

class LocalKubeConfigTokenProviderTest extends AbstractSpec {

  def "Loads token from kube config file"() {

    given:
      def folder = this.getClass().simpleName
      def resourcePath = "${folder}/kube/config"
      def kubeConfigFile = this.getClass().getResource(resourcePath).file
    expect:
      new LocalKubeConfigTokenProvider().getTokenFromKubeConfig(new File(kubeConfigFile)) == 'S9ZPY_vxB3fZhv2sDGVUcPmWqOA0jn2rcRKSPF7yrIk'
  }
}
