package no.skatteetaten.aurora.boober.service

import assertk.assertThat
import assertk.assertions.isNotNull
import com.fasterxml.jackson.module.kotlin.convertValue
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.Service
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OpenShiftObjectGeneratorServiceTest : AbstractOpenShiftObjectGeneratorTest() {

    lateinit var objectGenerator: OpenShiftObjectGenerator

    @BeforeEach
    fun setupTest() {
        objectGenerator = createObjectGenerator()
    }

    @Test
    fun `service target must refer to toxiproxy if toxiproxy is enabled in deployment spec for java`() {

        val deploymentSpec = specJavaWithToxiproxy()

        val svc = objectGenerator.generateService(
            auroraDeploymentSpecInternal = deploymentSpec,
            serviceLabels = emptyMap(),
            reference = OwnerReference()
        )!!

        val service = mapper.convertValue<Service>(svc)

        val ports = service.spec.ports
        assertThat(ports.find { it.name == "http" && it.port == 80 && it.targetPort.intVal == 8090 }).isNotNull()
    }
}
