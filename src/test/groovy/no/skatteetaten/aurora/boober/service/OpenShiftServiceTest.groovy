package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.LoggingUtilsKt.setLogLevels
import static no.skatteetaten.aurora.boober.utils.SampleFilesCollector.getUtvReferanseSampleFiles

import org.apache.velocity.app.VelocityEngine

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import groovy.json.JsonOutput
import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.model.AocConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import spock.lang.Specification

class OpenShiftServiceTest extends Specification {

  def setupSpec() {
    setLogLevels()
  }

  Configuration configuration = new Configuration()
  VelocityEngine velocityEngine = configuration.velocity()
  ObjectMapper mapper = configuration.mapper()

  def openShiftService = new OpenShiftService(velocityEngine, mapper)
  def validationService = new ValidationService()
  def aocConfigParserService = new AocConfigParserService(validationService)

  def "Should create six OpenShift objects from Velocity templates"() {
    given:
      Map<String, JsonNode> files = getUtvReferanseSampleFiles()

    when:
      def aocConfig = new AocConfig(files)
      AuroraDeploymentConfig auroraDc = aocConfigParserService.
          createConfigFromAocConfigFiles(aocConfig, "utv", "referanse")
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
            "name": "aot-utv",
            "labels": {
              "updatedBy" : "hero",
              "affiliation": "aot",
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
            "name": "refapp",
            "labels": {
              "app": "refapp",
              "updatedBy" : "hero",
              "affiliation": "aot"
            }
          },
          "spec": {
            "to": {
              "kind": "Service",
              "name": "refapp"
            }
          }
        }
      """)

      compareJson(configMap, """
        {
          "kind": "ConfigMap",
          "apiVersion": "v1",
          "metadata": {
            "name": "refapp",
            "labels": {
              "app": "refapp",
              "updatedBy" : "hero",
              "affiliation": "aot"
            }
          },
          "data": {
            "latest.properties": "SERVER_URL=http://localhost:8080"
          }
        }
      """)

      compareJson(service, """
        {
          "kind": "Service",
          "apiVersion": "v1",
          "metadata": {
            "name": "refapp",
            "annotations": {
              "sprocket.sits.no/service.webseal": "",
              "sprocket.sits.no/service.webseal-roles": "",
              "prometheus.io/scheme": "http",
              "prometheus.io/scrape": "true",
              "prometheus.io/path": "/prometheus",
              "prometheus.io/port": "8081"
        
            },
            "labels": {
              "app": "refapp",
              "updatedBy" : "hero",
              "affiliation": "aot"
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
              "name": "refapp"
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
            "name": "refapp",
            "labels": {
              "app": "refapp",
              "updatedBy" : "hero",
              "affiliation": "aot"
            }
          },
          "spec": {
            "dockerImageRepository": "docker-registry.aurora.sits.no:5000/ske_aurora_openshift_referanse/openshift-referanse-springboot-server",
            "tags": [{
              "name": "default",
              "from": {
                "kind": "DockerImage",
                "name": "docker-registry.aurora.sits.no:5000/ske_aurora_openshift_referanse/openshift-referanse-springboot-server:1"
              },
              "importPolicy": {
                "scheduled": true
              }
            }]
          }
        }
      """)

      compareJson(deploymentConfig, """
        {
          "kind": "DeploymentConfig",
          "apiVersion": "v1",
          "metadata": {
            "annotations": {
              "marjory.skatteetaten.no/management-path": ":8081/actuator",
              "marjory.skatteetaten.no/alarm": "true",
              "sprocket.sits.no/deployment-config.certificate": "ske.aurora.openshift.referanse.refapp",
              "sprocket.sits.no/deployment-config.database": "referanseapp"
            },
            "labels": {
              "app": "refapp",
              "updatedBy" : "hero",
              "affiliation": "aot"
            },
            "name": "refapp"
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
                        "refapp"
                      ],
                      "from": {
                        "name": "refapp:default",
                        "kind": "ImageStreamTag"
                      }
                    }
                  }
            ],
            "replicas": 3,
            "selector": {
              "name": "refapp"
            },
            "template": {
              "metadata": {
                "labels": {
                  "name": "refapp",
                  "updatedBy" : "hero",
                  "affiliation": "aot"
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
                      "name": "refapp"
                    }
                  }
                ],
                "containers": [
                  {
                    "name": "refapp",
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
                        "value": "refapp"
                      },        
                      {
                        "name": "ROUTE_NAME",
                        "value": "http://refapp-aot-utv.utv.paas.skead.no"
                      }
                    ],
                    "resources": {
                      "limits": {
                        "cpu": "2000m",
                        "memory": "256Mi"
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
