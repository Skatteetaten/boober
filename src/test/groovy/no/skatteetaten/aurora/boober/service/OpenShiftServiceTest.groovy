package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.utils.SampleFilesCollector.getQaEbsUsersSampleFiles

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import com.fasterxml.jackson.databind.JsonNode

import groovy.json.JsonOutput
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [no.skatteetaten.aurora.boober.Configuration,
    OpenShiftResourceClient,
    OpenShiftClient,
    EncryptionService,
    AuroraDeploymentConfigService,
    GitService, OpenShiftService, Config])
class OpenShiftServiceTest extends Specification {

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "verify-ebs-users"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    UserDetailsProvider userDetailsProvider() {
      factory.Mock(UserDetailsProvider)
    }

    @Bean
    GitService gitService() {
      factory.Mock(GitService)
    }

    @Bean
    OpenShiftClient openshiftClient() {
      factory.Mock(OpenShiftClient)
    }
  }

  @Autowired
  OpenShiftService openShiftService

  @Autowired
  UserDetailsProvider userDetailsProvider

  @Autowired
  AuroraDeploymentConfigService auroraDeploymentConfigService

  def "Should create OpenShift objects from Velocity templates"() {
    given:
      userDetailsProvider.authenticatedUser >> new User("hero", "token", "Test User")
      Map<String, JsonNode> files = getQaEbsUsersSampleFiles()

    when:
      def auroraConfig = new AuroraConfig(files.collect { new AuroraConfigFile(it.key, it.value, false) }, [:])
      AuroraDeploymentConfig auroraDc = auroraDeploymentConfigService.createAuroraDc(aid, auroraConfig, [])
      List<JsonNode> generatedObjects = openShiftService.generateObjects(auroraDc)

      def service = generatedObjects.find { it.get("kind").asText() == "Service" }
      def imageStream = generatedObjects.find { it.get("kind").asText() == "ImageStream" }
      def deploymentConfig = generatedObjects.find { it.get("kind").asText() == "DeploymentConfig" }
      def route = generatedObjects.find { it.get("kind").asText() == "Route" }
      def project = generatedObjects.find { it.get("kind").asText() == "ProjectRequest" }
      def buildConfig = generatedObjects.find { it.get("kind").asText() == "BuildConfig" }
      def rolebindings = generatedObjects.find { it.get("kind").asText() == "RoleBinding" }

    then:
      generatedObjects.size() == 7


      compareJson(rolebindings, """
        {
               "kind": "RoleBinding",
               "apiVersion": "v1",
               "metadata": {
                   "name": "admin"
               },
               "groupNames": [
                   "APP_PaaS_drift",
                   "APP_PaaS_utv"
               ],
               "userNames": [
                   "foo"
               ],
               "subjects": [
                   {
                       "kind": "User",
                       "name": "foo"
                   },
                   {
                       "kind": "Group",
                       "name": "APP_PaaS_drift"
                   },
                   {
                       "kind": "Group",
                       "name": "APP_PaaS_utv"
                   }
               ],
               "roleRef": {
                   "name": "admin"
               }
           }
      """)


      compareJson(project, """
        {
          "kind": "ProjectRequest",
          "apiVersion": "v1",
          "metadata": {
            "name": "aos-booberdev"
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


      compareJson(service, """
        {
          "kind": "Service",
          "apiVersion": "v1",
          "metadata": {
            "name": "verify-ebs-users",
            "annotations": {
              "prometheus.io/scrape": "false"
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

      compareJson(buildConfig, """
      {
        "kind": "BuildConfig",
        "apiVersion": "v1",
        "metadata": {
          "name": "verify-ebs-users",
          "labels": {
            "app": "verify-ebs-users",
            "updatedBy": "hero",
            "affiliation": "aos"
          }
        },
        "spec": {
          "triggers": [
            {
              "type": "ImageChange",
              "imageChange": {
                "from": {
                  "kind": "ImageStreamTag",
                  "namespace": "openshift",
                  "name": "oracle8:1"
                }
              }
            },
            {
              "type": "ImageChange",
              "imageChange": {}
            }
          ],
          "strategy": {
            "type": "Custom",
            "customStrategy": {
              "from": {
                "kind": "ImageStreamTag",
                "namespace": "openshift",
                "name": "leveransepakkebygger:prod"
              },
              "env": [
                {
                  "name": "ARTIFACT_ID",
                  "value": "verify-ebs-users"
                },
                {
                  "name": "GROUP_ID",
                  "value": "ske.admin.lisens"
                },
                {
                  "name": "VERSION",
                  "value": "1.0.3-SNAPSHOT"
                },
                {
                  "name": "DOCKER_BASE_VERSION",
                  "value": "1"
                },
                {
                  "name": "DOCKER_BASE_IMAGE",
                  "value": "aurora/oracle8"
                },
                {
                  "name": "PUSH_EXTRA_TAGS",
                  "value": "latest,major,minor,patch"
                }
              ],
              "exposeDockerSocket": true
            }
          },
          "output": {
            "to": {
              "kind": "ImageStreamTag",
              "name": "verify-ebs-users:latest"
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
