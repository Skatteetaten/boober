package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import no.skatteetaten.aurora.boober.model.openshift.Alerts
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.applicationErrorResult
import no.skatteetaten.aurora.boober.utils.singleApplicationErrorResult
import org.junit.jupiter.api.Test
import assertk.assertions.containsOnly

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
            AlertsFeature.Errors.MissingAlertDelayProp.message,
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
    fun `should generate alert resource with multipl connection rules`() {
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
}
