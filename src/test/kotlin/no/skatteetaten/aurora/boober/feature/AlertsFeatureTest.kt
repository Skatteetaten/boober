package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.Test
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsOnly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import no.skatteetaten.aurora.boober.model.openshift.Alerts
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.applicationErrorResult
import no.skatteetaten.aurora.boober.utils.singleApplicationErrorResult

class AlertsFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = AlertsFeature()

    @Test
    fun `should fail validation when expression is missing`() {
        assertThat {
            createAuroraDeploymentContext(
                """{
                  "alerts": {
                    "is-down": {
                      "enabled": true,
                      "delay": 1,
                      "connection": "aurora-mattermost",
                      "severity": "warning"
                    }
                  }
                }"""
            )
        }.singleApplicationErrorResult(AlertsFeature.Errors.MissingAlertExpression.message)
    }

    @Test
    fun `should fail validation when missing configuration properties`() {
        assertThat {
            createAuroraDeploymentContext(
                """{
                      "alerts": {
                        "is-down": {
                          "enabled": true
                        }
                      }
                    }"""
            )
        }.applicationErrorResult(
            AlertsFeature.Errors.MissingAlertExpression.message,
            AlertsFeature.Errors.MissingAlertConnectionProp.message,
            AlertsFeature.Errors.MissingAlertSeverity.message
        )
    }

    @Test
    fun `should not generate alert resource if enabled is false`() {
        val generatedResource = generateResources(
            """{
              "alerts": {
                "isdown": {
                  "enabled": false,
                  "expr": "ac",
                  "delay": 1,
                  "connection": "aurora-mattermost",
                  "severity": "warning"
                }
              }
            }"""
        )

        assertThat(generatedResource).isEmpty()
    }

    @Test
    fun `should generate alert resource where enabled is when multiple configurations`() {
        val generatedResources = generateResources(
            """{
              "alerts": {
                "isdown": {
                  "enabled": false,
                  "expr": "ac",
                  "delay": 1,
                  "connection": "aurora-mattermost",
                  "severity": "warning"
                },
                "halted": {
                  "enabled": true,
                  "expr": "av",
                  "delay": 1,
                  "connection": "aurora-mattermost",
                  "severity": "warning"
                }
              }
            }"""
        )

        assertThat(generatedResources).hasSize(1)
        val resource = generatedResources[0].resource as Alerts
        val alertSpec = resource.spec
        val metadata = resource.metadata
        assertThat(alertSpec.prometheus.expr).isEqualTo("av")
        assertThat(alertSpec.alert.enabled).isTrue()
        assertThat(metadata.name).contains("simple-")
    }

    @Test
    fun `should generate alert resource with multiple connection rules`() {
        val generatedResources = generateResources(
            """{
              "alerts": {
                "isdown": {
                  "enabled": true,
                  "expr": "ac",
                  "delay": 1,
                  "connections": [
                    "aurora-mattermost",
                    "connection2"
                  ],
                  "severity": "warning"
                }
              }
            }"""
        )

        assertThat(generatedResources).hasSize(1)
        val resource = generatedResources[0].resource as Alerts
        val alertSpec = resource.spec
        val metadata = resource.metadata
        assertThat(alertSpec.prometheus.expr).isEqualTo("ac")
        assertThat(alertSpec.alert.enabled).isTrue()
        assertThat(metadata.name).contains("simple-")
    }

    @Test
    fun `should use configuration from alertsDefaults if defined for missing configuration values`() {
        val generatedResources = generateResources(
            """{
              "alertsDefaults": {
                "enabled": true,
                "connection": "koblingsregel",
                "delay": 4
              },
              "alerts": {
                "my-alert": {
                  "expr": "en_metrikk_query('targetLabel') > 10",
                  "severity": "critical"
                }
              }
            }"""
        )

        assertThat(generatedResources).hasSize(1)
        val alert = (generatedResources[0].resource as Alerts).spec.alert
        assertThat(alert.delay).isEqualTo("4")
        assertThat(alert.connections).containsOnly("koblingsregel")
        assertThat(alert.severity).isEqualTo("critical")
    }

    @Test
    fun `should override alertsDefaults configuration if property is defined in alert configuration`() {
        val generatedResources = generateResources(
            """{
              "alertsDefaults": {
                "enabled": false,
                "connection": "koblingsregel",
                "delay": 4
              },
              "alerts": {
                "my-alert": {
                  "enabled": true,
                  "expr": "en_metrikk_query('targetLabel') > 10",
                  "severity": "critical",
                  "connection": "en-annen-koblingsregel",
                  "delay": 2
                }
              }
            }"""
        )

        assertThat(generatedResources).hasSize(1)
        val alert = (generatedResources[0].resource as Alerts).spec.alert
        assertThat(alert.delay).isEqualTo("2")
        assertThat(alert.connections).containsOnly("en-annen-koblingsregel")
    }

    @Test
    fun `should fail validation when legacy connection and new connections is defined`() {
        assertThat {
            createAuroraDeploymentContext(
                """{
                  "alertsDefaults": {
                    "connections": [
                      "aurora-email"
                    ]
                  },
                  "alerts": {
                    "is-down": {
                      "enabled": true,
                      "delay": 1,
                      "expr": "test",
                      "connection": "aurora-mattermost",
                      "severity": "warning"
                    }
                  }
                }"""
            )
        }.singleApplicationErrorResult(AlertsFeature.Errors.InvalidLegacyConnectionAndConnectionsProp.message)
    }

    @Test
    fun `when using legacy connection property then legacy check should return true`() {
        val spec = createAuroraDeploymentSpecForFeature(
            """{
                  "alerts": {
                    "is-down": {
                      "enabled": true,
                      "delay": "1",
                      "expr": "test",
                      "connection": "aurora-mattermost",
                      "severity": "warning"
                    }
                  }
                }""".trimMargin()
        )
        val alertsFeature = feature as AlertsFeature
        val containsDeprecatedConnection = alertsFeature.containsDeprecatedConnection(spec)
        assertThat(containsDeprecatedConnection).isTrue()
    }

    @Test
    fun `when using connections property then legacy check should false`() {
        val spec = createAuroraDeploymentSpecForFeature(
            """{
                  "alerts": {
                    "is-down": {
                      "enabled": true,
                      "delay": "1",
                      "expr": "test",
                      "connections": ["aurora-mattermost"],
                      "severity": "warning"
                    }
                  }
                }""".trimMargin()
        )
        val alertsFeature = feature as AlertsFeature
        val containsDeprecatedConnection = alertsFeature.containsDeprecatedConnection(spec)
        assertThat(containsDeprecatedConnection).isFalse()
    }

    @Test
    fun `when alert is not configured then legacy check should return false`() {
        val spec = createAuroraDeploymentSpecForFeature("""{}""")
        val alertsFeature = feature as AlertsFeature
        assertThat(alertsFeature.containsDeprecatedConnection(spec)).isFalse()
    }
}
