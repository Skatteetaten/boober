package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import groovy.json.JsonOutput
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [no.skatteetaten.aurora.boober.Configuration,
    OpenShiftTemplateProcessor,
    Config])
class OpenShiftTemplateProcessorTest extends Specification {

  public static final String APP_NAME = "verify-ebs-users"

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    OpenShiftResourceClient client() {
      factory.Mock(OpenShiftResourceClient)
    }

  }

  @Autowired
  OpenShiftResourceClient client

  @Autowired
  OpenShiftTemplateProcessor service

  @Autowired
  ObjectMapper mapper

  def "Should create objects from processing templateFile"() {
    given:
      def template = this.getClass().getResource("/openshift-objects/atomhopper.json")
      def templateResult = this.getClass().getResource("/openshift-objects/atomhopper-new.json")
      JsonNode jsonResult = mapper.readTree(templateResult)

      def adc = TestDataKt.generateProccessADC("openshift-objects/atomhopper.json", mapper.readTree(template))

    when:

      client.post("processedtemplate", null, adc.namespace, _) >>
          new ResponseEntity<JsonNode>(jsonResult, HttpStatus.OK)

      def generatedObjects = service.generateObjects(adc)

    then:
      generatedObjects.size() == 4
      def dc = generatedObjects.find { it.get("kind").asText() == "DeploymentConfig" }

      compareJson(dc, """{
        "apiVersion": "v1",
        "kind": "DeploymentConfig",
        "metadata": {
          "annotations": {
            "sprocket.sits.no/deployment-config.database": "tvinn"
          },
          "labels": {
            "name": "tvinn",
            "affiliation": "safir"
          },
          "name": "tvinn"
        },
        "spec": {
          "replicas": 1,
          "selector": {
            "name": "tvinn"
          },
          "strategy": {
            "resources": {

            },
            "rollingParams": {
              "intervalSeconds": 1,
              "maxSurge": "25%",
              "maxUnavailable": 0,
              "timeoutSeconds": 120,
              "updatePeriodSeconds": 1
            },
            "type": "Rolling"
          },
          "template": {
            "metadata": {
              "labels": {
                "name": "tvinn"
              }
            },
            "spec": {
              "containers": [
                  {
                    "capabilities": {

                  },
                    "env": [
                      {
                        "name": "SPLUNK_INDEX",
                        "value": "safir"
                      },
                      {
                        "name": "POD_NAME",
                        "valueFrom": {
                        "fieldRef": {
                          "apiVersion": "v1",
                          "fieldPath": "metadata.name"
                        }
                      }
                      },
                      {
                        "name": "POD_NAMESPACE",
                        "valueFrom": {
                        "fieldRef": {
                          "apiVersion": "v1",
                          "fieldPath": "metadata.namespace"
                        }
                      }
                      },
                      {
                        "name": "JAVA_OPTS",
                        "value": "-Xmx4g -Xms2g"
                      },
                      {
                        "name": "splunk_config_stanzas",
                        "value": "# --- start/stanza STDOUT\n[monitor://./logs/*.log]\ndisabled = false\nfollowTail = 0\nsourcetype = log4j\nindex = INDEX-PLACEHOLDER\n_meta = environment::NAMESPACE-PLACEHOLDER application::tvinn nodetype::openshift\nhost = openshift-host\n# --- end/stanza\n\n# --- start/stanza ACCESS_LOG\n[monitor://./logs/*.access]\ndisabled = false\nfollowTail = 0\nsourcetype = access_combined\nindex = INDEX-PLACEHOLDER\n_meta = environment::NAMESPACE-PLACEHOLDER application::tvinn nodetype::openshift\nhost = openshift-host\n# --- end/stanza\n\n# --- start/stanza GC LOG\n[monitor://./logs/*.gc]\ndisabled = false\nfollowTail = 0\nsourcetype = gc_log\nindex = INDEX-PLACEHOLDER\n_meta = environment::NAMESPACE-PLACEHOLDER application::tvinn nodetype::openshift\nhost = openshift-host\n# --- end/stanza\n\n"
                      },
                      {
                        "name": "FEED_NAME",
                        "value": "tolldeklarasjon"
                      },
                      {
                        "name": "DB_NAME",
                        "value": "tvinn"
                      },
                      {
                        "name": "HOST_NAME",
                        "value": "localhost"
                      },
                      {
                        "name": "SCHEME",
                        "value": "http"
                      }
                  ],
                    "image": "tvinn",
                    "imagePullPolicy": "IfNotPresent",
                    "name": "tvinn",
                    "ports": [
                      {
                        "containerPort": 8080,
                        "protocol": "TCP"
                      },
                      {
                        "containerPort": 8778,
                        "name": "jolokia"
                      }
                  ],
                    "resources": {
                    "limits": {
                      "cpu": "2",
                      "memory": "4gi"
                    },
                    "requests": {
                      "cpu": "0.1",
                      "memory": "128Mi"
                    }
                  },
                    "securityContext": {
                    "capabilities": {

                    },
                    "privileged": false
                  },
                    "terminationMessagePath": "/dev/termination-log",
                    "volumeMounts": [
                      {
                        "mountPath": "/u01/logs",
                        "name": "application-log-volume"
                      }
                  ]
                  }
              ],
              "dnsPolicy": "ClusterFirst",
              "restartPolicy": "Always",
              "serviceAccount": "",
              "serviceAccountName": "",
              "volumes": [
                  {
                    "emptyDir": {

                  },
                    "name": "application-log-volume"
                  }
              ]
            }
          },
          "triggers": [
              {
                "imageChangeParams": {
                "automatic": true,
                "containerNames": [
                    "tvinn"
                ],
                "from": {
                  "kind": "ImageStreamTag",
                  "name": "atomhopper:1.2.0"
                },
                "lastTriggeredImage": ""
              },
                "type": "ImageChange"
              }
          ]
        }
      }""")

  }

  def compareJson(JsonNode jsonNode, String jsonString) {
    assert JsonOutput.prettyPrint(jsonNode.toString()) == JsonOutput.prettyPrint(jsonString)
    true
  }
}
