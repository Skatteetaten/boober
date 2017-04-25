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
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [no.skatteetaten.aurora.boober.Configuration,
    ProcessService,
    Config])
class ProcessServiceTest extends Specification {

  public static final String APP_NAME = "verify-ebs-users"

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    OpenshiftResourceClient client() {
      factory.Mock(OpenshiftResourceClient)
    }

  }

  @Autowired
  OpenshiftResourceClient client

  @Autowired
  ProcessService service

  @Autowired
  ObjectMapper mapper

  def "Should create objects from processing templateFile"() {
    given:
      def template = this.getClass().getResource("/openshift-objects/atomhopper.json")
      def templateResult = this.getClass().getResource("/openshift-objects/atomhopper-new.json")
      JsonNode jsonResult = mapper.readTree(templateResult)

      def adc = TestDataKt.generateProccessADC(mapper.readValue(template, Map.class))

    when:

      client.post("processedtemplate", null, adc.namespace, _) >>
          new ResponseEntity<JsonNode>(jsonResult, HttpStatus.OK)

      def generatedObjects = service.generateObjects(adc)
      def imageStream = generatedObjects.find { it.get("kind").asText() == "ImageStream" }
      def service = generatedObjects.find { it.get("kind").asText() == "Service" }
      def route = generatedObjects.find { it.get("kind").asText() == "Route" }
      def deploymentConfig = generatedObjects.find { it.get("kind").asText() == "DeploymentConfig" }

    then:
      generatedObjects.size() == 4


      compareJson(route, """
        {
          "kind": "Route",
          "apiVersion": "v1",
          "metadata": {
            "name": "tvinn",
            "labels": {
              "app": "tvinn",
              "updatedBy" : "hero",
              "affiliation": "aos"
            }
          },
          "spec": {
            "to": {
              "kind": "Service",
              "name": "tvinn"
            }
          }
        }
      """)


      compareJson(service, """
        {
          "kind": "Service",
          "apiVersion": "v1",
          "metadata": {
            "name": "verify-ebs-users",
            "annotations": {
              "sprocket.sits.no/service.webseal": "",
              "sprocket.sits.no/service.webseal-roles": "",
              "prometheus.io/scheme": "http",
              "prometheus.io/scrape": "true",
              "prometheus.io/path": "/prometheus",
              "prometheus.io/port": "8080"
        
            },
            "labels": {
              "app": "verify-ebs-users",
              "updatedBy" : "hero",
              "affiliation": "aos"
            }
          },
          "spec": {
            "ports": [
              {
                "name": "http",
                "protocol": "TCP",
                "port": 80,
                "targetPort": 8080,
                "nodePort": 0
              }
            ],
            "selector": {
              "name": "verify-ebs-users"
            },
            "type": "ClusterIP",
            "sessionAffinity": "None"
          }
        }
      """)

      compareJson(imageStream, """
        {
          "kind": "ImageStream",
          "apiVersion": "v1",
          "metadata": {
            "name": "verify-ebs-users",
            "labels": {
              "app": "verify-ebs-users",
              "updatedBy" : "hero",
              "affiliation": "aos"
            }
          }
        }
      """)

      compareJson(deploymentConfig, """
        {
          "kind": "DeploymentConfig",
          "apiVersion": "v1",
          "metadata": {
            "annotations": {
              "marjory.skatteetaten.no/management-path": "",
              "marjory.skatteetaten.no/alarm": "true",
              "sprocket.sits.no/deployment-config.certificate": "ske.admin.lisens.verify-ebs-users"
            },
            "labels": {
              "app": "verify-ebs-users",
              "updatedBy" : "hero",
              "affiliation": "aos"
            },
            "name": "verify-ebs-users"
          },
          "spec": {
             "strategy": {
              "type" : "Rolling",
              "rollingParams": {
                "intervalSeconds": 1,
                "maxSurge": "25%",
                "maxUnavailable": 0,
                "timeoutSeconds": 120,
                "updatePeriodSeconds": 1
              }
            },
            "triggers": [
                  {
                    "type": "ImageChange",
                    "imageChangeParams": {
                      "automatic": true,
                      "containerNames": [
                        "verify-ebs-users"
                      ],
                      "from": {
                        "name": "verify-ebs-users:latest",
                        "kind": "ImageStreamTag"
                      }
                    }
                  }
            ],
            "replicas": 1,
            "selector": {
              "name": "verify-ebs-users"
            },
            "template": {
              "metadata": {
                "labels": {
                  "name": "verify-ebs-users",
                  "updatedBy" : "hero",
                  "affiliation": "aos"
                }
              },
              "spec": {
                "volumes": [
                  {
                    "name": "application-log-volume",
                    "emptyDir": {}
                  },
                  {
                    "name": "config",
                    "configMap": {
                      "name": "verify-ebs-users"
                    }
                  }
                ],
                "containers": [
                  {
                    "name": "verify-ebs-users",
                    "ports": [
                      {
                        "containerPort": 8080,
                        "protocol": "TCP",
                        "name": "http"
                      },
                      {
                        "containerPort": 8081,
                        "protocol": "TCP",
                        "name": "management"
                      },
                      {
                        "containerPort": 8778,
                        "name": "jolokia"
                      }
                    ],
                    "env": [
                      {
                        "name": "SPLUNK_INDEX",
                        "value": "openshift-test"
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
                        "name": "HTTP_PORT",
                        "value": "8080"
                      },
                      {
                        "name": "MANAGEMENT_HTTP_PORT",
                        "value": "8081"
                      },
                      {
                        "name": "APP_NAME",
                        "value": "verify-ebs-users"
                      },        
                      {
                        "name": "ROUTE_NAME",
                        "value": "http://verify-ebs-users-aos-booberdev.utv.paas.skead.no"
                      }
                    ],
                    "resources": {
                      "limits": {
                        "cpu": "2000m",
                        "memory": "128Mi"
                      },
                      "requests": {
                        "cpu": "0",
                        "memory": "128Mi"
                      }
                    },
                    "volumeMounts": [
                      {
                        "name": "application-log-volume",
                        "mountPath": "/u01/logs"
                      },
                      {
                        "name": "config",
                        "mountPath": "/u01/config/configmap"
                      }
                    ],
                    "terminationMessagePath": "/dev/termination-log",
                    "imagePullPolicy": "IfNotPresent",
                    "capabilities": {},
                    "securityContext": {
                      "capabilities": {},
                      "privileged": false
                    },
                    "livenessProbe": {
                      "exec": {
                        "command": [
                          "/u01/application/bin/liveness.sh"
                        ]
                      },
                      "initialDelaySeconds": 10,
                      "timeoutSeconds": 1
                    },
                    "readinessProbe": {
                      "exec": {
                        "command": [
                          "/u01/application/bin/readiness.sh"
                        ]
                      },
                      "initialDelaySeconds": 10,
                      "timeoutSeconds": 1
                    }
        
                  }
                ],
                "restartPolicy": "Always",
                "dnsPolicy": "ClusterFirst"
              }
            }
          }
        }

      """)

  }

  def compareJson(JsonNode jsonNode, String jsonString) {
    assert JsonOutput.prettyPrint(jsonNode.toString()) == JsonOutput.prettyPrint(jsonString)
    true
  }
}
