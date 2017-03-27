package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.LoggingUtilsKt.setLogLevels
import static no.skatteetaten.aurora.boober.utils.SampleFilesCollector.getUtvReferanseSampleFiles

import org.apache.velocity.app.VelocityEngine

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import groovy.json.JsonSlurper
import no.skatteetaten.aurora.boober.Configuration
import spock.lang.Specification

class OpenShiftServiceTest extends Specification {

  def setupSpec() {
    setLogLevels()
  }
  Configuration configuration = new Configuration()
  VelocityEngine velocityEngine = configuration.velocity()
  ObjectMapper mapper = configuration.mapper()

  def openShiftService = new OpenshiftService(velocityEngine)
  def configService = new ConfigService(mapper)

  def "Should create six OpenShift objects from Velocity templates"() {
    given:
      def slurper = new JsonSlurper()
      Map<String, JsonNode> files = getUtvReferanseSampleFiles()

    when:
      def booberResult = configService.createConfigFormAocConfigFiles("utv", "referanse", files)
      def openShiftResult = openShiftService.generateObjects(booberResult, "hero")

      def configMap = slurper.parseText(openShiftResult.openshiftObjects.get("configmaps").toString())
      def service = slurper.parseText(openShiftResult.openshiftObjects.get("services").toString())
      def imageStream = slurper.parseText(openShiftResult.openshiftObjects.get("imagestreams").toString())
      def deploymentConfig = slurper.parseText(openShiftResult.openshiftObjects.get("deploymentconfigs").toString())
      def route = slurper.parseText(openShiftResult.openshiftObjects.get("routes").toString())
      def project = slurper.parseText(openShiftResult.openshiftObjects.get("projects").toString())

    then:
      openShiftResult.openshiftObjects.size() == 6

      project.toString() == slurper.parseText("""
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
      """).toString()

      route.toString() == slurper.parseText("""
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
      """).toString()

      configMap.toString() == slurper.parseText("""
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
      """).toString()

      service.toString() == slurper.parseText("""
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
      """).toString()

      imageStream.toString() == slurper.parseText("""
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
      """).toString()

      deploymentConfig.toString() == slurper.parseText("""
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

      """).toString()
  }
}
