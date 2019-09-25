package no.skatteetaten.aurora.boober.feature

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentCommand
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.utils.durationString
import org.springframework.stereotype.Service

@Service
class ApplicationDeploymentFeature() : Feature{

    //message, ttl
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraDeploymentCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
                AuroraConfigFieldHandler("message"),
                AuroraConfigFieldHandler("ttl", validator = { it.durationString() })
        )
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
    /*
    val appId: String
        get() =
            template?.let {
                it.template
            } ?: localTemplate?.let {
                "local" + it.templateJson.openshiftName
            } ?: deploy?.let {
                "${it.groupId}/${it.artifactId}"
            } ?: throw RuntimeException("Not valid deployment")

             val appName: String
        get() =
            template?.let {
                it.template
            } ?: localTemplate?.let {
                "local" + it.templateJson.openshiftName
            } ?: deploy?.let {
                it.artifactId
            } ?: throw RuntimeException("Not valid deployment")

             @JvmStatic
    fun generate(
        deploymentSpecInternal: AuroraDeploymentSpecInternal,
        deployId: String,
        cmd: ApplicationDeploymentCommand,
        updateBy: String,
        provisions: Provisions
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
                databases = provisions.dbhSchemas.map { it.id },
                splunkIndex = deploymentSpecInternal.integration?.splunkIndex,
                managementPath = deploymentSpecInternal.deploy?.managementPath,
                releaseTo = deploymentSpecInternal.deploy?.releaseTo,
                command = cmd,
                message = deploymentSpecInternal.message
            ),
            _metadata = ObjectMetaBuilder()
                .withName(deploymentSpecInternal.name)
                .withNamespace(deploymentSpecInternal.environment.namespace)
                .withLabels(
                    mapOf(
                        "app" to deploymentSpecInternal.name,
                        "name" to deploymentSpecInternal.name,
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
     */
}