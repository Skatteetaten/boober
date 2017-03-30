package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.LoggingUtilsKt.setLogLevels
import static no.skatteetaten.aurora.boober.utils.SampleFilesCollector.getQaEbsUsersSampleFiles

import org.apache.velocity.app.VelocityEngine

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import groovy.json.JsonOutput
import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import spock.lang.Specification

class OpenShiftServiceTest extends Specification {

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "verify-ebs-users"

  def setupSpec() {
    setLogLevels()
  }

  Configuration configuration = new Configuration()
  VelocityEngine velocityEngine = configuration.velocity()
  ObjectMapper mapper = configuration.mapper()

  def openShiftService = new OpenShiftService(velocityEngine, mapper)
  def validationService = new ValidationService()
  def aocConfigParserService = new AuroraConfigParserService(validationService)

  def "Should create six OpenShift objects from Velocity templates"() {
    given:
      Map<String, Map<String, Object>> files = getQaEbsUsersSampleFiles()

    when:
      def aocConfig = new AuroraConfig(files)
      AuroraDeploymentConfig auroraDc = aocConfigParserService.
          createAuroraDcFromAuroraConfig(aocConfig, ENV_NAME, APP_NAME)
      List<JsonNode> generatedObjects = openShiftService.generateObjects(auroraDc, "hero")

      def configMap = generatedObjects.find { it.get("kind").asText() == "ConfigMap" }
      def service = generatedObjects.find { it.get("kind").asText() == "Service" }
      def imageStream = generatedObjects.find { it.get("kind").asText() == "ImageStream" }
      def deploymentConfig = generatedObjects.find { it.get("kind").asText() == "DeploymentConfig" }
      def route = generatedObjects.find { it.get("kind").asText() == "Route" }
      def project = generatedObjects.find { it.get("kind").asText() == "Project" }

    then:

      compareJson(project, """
        {
          "kind": "Project",
          "apiVersion": "v1",
          "metadata": {
            "name": "aos-booberdev",
            "labels": {
              "updatedBy" : "hero",
              "affiliation": "aos",
              "openshift.io/requester": "hero"
            }
          }
        }
      """)

      compareJson(route, """
        {
          "kind": "Route",
          "apiVersion": "v1",
          "metadata": {
            "name": "verify-ebs-users",
            "labels": {
              "app": "verify-ebs-users",
              "updatedBy" : "hero",
              "affiliation": "aos"
            }
          },
          "spec": {
            "to": {
              "kind": "Service",
              "name": "verify-ebs-users"
            }
          }
        }
      """)

      compareJson(configMap, """
        {
          "kind": "ConfigMap",
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
                        "name": "verify-ebs-users:default",
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
                        "value": "http://verify-ebs-users-aos-booberdev.qa.paas.skead.no"
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
