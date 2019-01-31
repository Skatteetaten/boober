package no.skatteetaten.aurora.boober.service.internal

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentSpec
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ProvisioningResult
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionResults
import no.skatteetaten.aurora.boober.utils.Instants
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.apache.commons.codec.digest.DigestUtils

object ApplicationDeploymentGenerator {

    @JvmStatic
    fun generate(
        deploymentSpecInternal: AuroraDeploymentSpecInternal,
        deployId: String,
        cmd: ApplicationDeploymentCommand,
        updateBy: String,
        provisioningResult: ProvisioningResult
    ): ApplicationDeployment {

        val ttl = deploymentSpecInternal.deploy?.ttl?.let {
            val removeInstant = Instants.now + it
            "removeAfter" to removeInstant.epochSecond.toString()
        }
        val applicationId = DigestUtils.sha1Hex(deploymentSpecInternal.appId)
        val applicationDeploymentId = DigestUtils.sha1Hex(deploymentSpecInternal.appDeploymentId)
        return ApplicationDeployment(
            spec = ApplicationDeploymentSpec(
                selector = mapOf("name" to deploymentSpecInternal.name),
                deployTag = deploymentSpecInternal.version,
                applicationId = applicationId,
                applicationDeploymentId = applicationDeploymentId,
                applicationName = deploymentSpecInternal.appName,
                applicationDeploymentName = deploymentSpecInternal.name,
                database = createDatabaseInformation(provisioningResult.schemaProvisionResults),
                splunkIndex = deploymentSpecInternal.integration?.splunkIndex,
                managementPath = deploymentSpecInternal.deploy?.managementPath,
                releaseTo = deploymentSpecInternal.deploy?.releaseTo,
                command = cmd,
                message = deploymentSpecInternal.message
            ),
            metadata = ObjectMetaBuilder()
                .withName(deploymentSpecInternal.name)
                .withLabels(
                    mapOf(
                        "app" to deploymentSpecInternal.name,
                        "updatedBy" to updateBy,
                        "affiliation" to deploymentSpecInternal.environment.affiliation,
                        "booberDeployId" to deployId,
                        "applicationId" to applicationId,
                        "id" to applicationDeploymentId
                    ).addIfNotNull(ttl)
                )
                .build()
        )
    }

    private fun createDatabaseInformation(schemaProvision: SchemaProvisionResults?): Map<String, String> {
        return schemaProvision?.results?.associate {
            it.dbhSchema.name to it.dbhSchema.id
        } ?: mapOf()
    }
}