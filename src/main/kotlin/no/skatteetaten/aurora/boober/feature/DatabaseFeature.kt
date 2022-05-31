package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.newVolume
import com.fkorotkov.kubernetes.newVolumeMount
import com.fkorotkov.kubernetes.secret
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeMount
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.Paths.secretsPath
import no.skatteetaten.aurora.boober.model.addVolumesAndMounts
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.service.ProvisioningException
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhSchema
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaForAppRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaIdRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionRequest
import no.skatteetaten.aurora.boober.utils.ConditionalOnPropertyMissingOrEmpty
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.filterNullValues
import no.skatteetaten.aurora.boober.utils.findResourcesByType
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import kotlin.reflect.KClass

private const val DATABASE_CONTEXT_KEY = "databases"

private val FeatureContext.databases: List<Database> get() = this.getContextKey(DATABASE_CONTEXT_KEY)

@ConditionalOnPropertyMissingOrEmpty("integrations.dbh.url")
@Service
class DatabaseDisabledFeature(
    @Value("\${openshift.cluster}") cluster: String
) : DatabaseFeatureTemplate(cluster) {

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {
        if (context.databases.isNotEmpty()) {
            return listOf(IllegalArgumentException("Databases are not supported in this cluster"))
        }
        return emptyList()
    }
}

@Service
@ConditionalOnProperty("integrations.dbh.url")
class DatabaseFeature(
    val databaseSchemaProvisioner: DatabaseSchemaProvisioner,
    val userDetailsProvider: UserDetailsProvider,
    @Value("\${openshift.cluster}") cluster: String
) : DatabaseFeatureTemplate(cluster) {

    private fun SchemaProvisionRequest.isAppRequestWithoutGenerate() = this is SchemaForAppRequest && !this.generate
    private fun SchemaProvisionRequest.isAppRequestWithoutIgnoreMissingSchema() = this is SchemaForAppRequest && !this.ignoreMissingSchema

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {
        val db = context.databases
        val databases = db.createSchemaRequests(adc)
        if (!fullValidation || adc.cluster != cluster || databases.isEmpty()) {
            return emptyList()
        }

        return databases
            .filter { it is SchemaIdRequest || (it.isAppRequestWithoutGenerate() && it.isAppRequestWithoutIgnoreMissingSchema()) }
            .mapNotNull { request ->
                try {
                    val schema = databaseSchemaProvisioner.findSchema(request)
                        ?: databaseSchemaProvisioner.findCooldownSchemaIfTryReuseEnabled(request)

                    when {
                        schema == null -> ProvisioningException(
                            "Could not find schema with name=${request.details.schemaName}"
                        )
                        schema.affiliation != request.details.affiliation -> ProvisioningException(
                            "Schema with id=${schema.id} is located in the affiliation=${schema.affiliation}, " +
                                "current affiliation=${request.details.affiliation}. " +
                                "Using schema with id across affiliations is not allowed"
                        )
                        else -> null
                    }
                } catch (e: Exception) {
                    e
                }
            }
    }

    override fun generateSequentially(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        val databases = context.databases

        val schemaRequests = databases.createSchemaRequests(adc)

        if (schemaRequests.isEmpty()) return emptySet()

        return schemaRequests.provisionSchemasAndAssociateWithRequest()
            .createDbhSecrets(adc)
            .generateAuroraResources()
            .toSet()
    }

    override fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: FeatureContext
    ) {
        val databases = context.databases
        if (databases.isEmpty()) return

        resources.attachDbSecrets(databases, adc.name, this::class)
        resources.addDatabaseIdsToApplicationDeployment()
    }

    private fun Set<AuroraResource>.addDatabaseIdsToApplicationDeployment() {
        val databaseIds = this.findResourcesByType<Secret>().mapNotNull {
            it.metadata?.labels?.get("dbhId")
        }

        this.filter { it.resource.kind == "ApplicationDeployment" }
            .map {
                modifyResource(it, "Added databaseId")
                val ad: ApplicationDeployment = it.resource as ApplicationDeployment
                ad.spec.databases = databaseIds
            }
    }

    private fun Set<AuroraResource>.attachDbSecrets(
        databases: List<Database>,
        appName: String,
        feature: KClass<out Feature>
    ) {
        val firstEnv = databases.firstOrNull()?.let {
            createDbEnv("${it.name}-db", "db")
        }
        val dbEnv = databases.flatMap { createDbEnv("${it.name}-db") }
            .addIfNotNull(firstEnv).toMap().toEnvVars()

        val volumeAndMounts = databases.map { it.createDatabaseVolumesAndMounts(appName) }

        val volumes = volumeAndMounts.map { it.first }
        val volumeMounts = volumeAndMounts.map { it.second }

        this.addVolumesAndMounts(dbEnv, volumes, volumeMounts, feature.java)
    }

    private fun List<SchemaProvisionRequest>.provisionSchemasAndAssociateWithRequest() =
        this.associateWith {
            databaseSchemaProvisioner.provisionSchema(it)
        }
            .filterNullValues()

    private fun Map<SchemaProvisionRequest, DbhSchema>.createDbhSecrets(adc: AuroraDeploymentSpec) =
        this.map { (request, dbhSchema) ->
            DbhSecretGenerator.createDbhSecret(
                dbhSchema = dbhSchema,
                secretName = request.getSecretName(prefix = adc.name),
                secretNamespace = adc.namespace
            )
        }

    fun Database.createDatabaseVolumesAndMounts(appName: String): Pair<Volume, VolumeMount> {
        val mountName = "${this.name}-db".lowercase()
        val volumeName = mountName.replace("_", "-").lowercase().ensureStartWith(appName, "-")

        val mount = newVolumeMount {
            name = volumeName
            mountPath = "$secretsPath/$mountName"
        }

        val volume =
            newVolume {
                name = volumeName
                secret {
                    secretName = volumeName
                }
            }
        return volume to mount
    }

    fun List<Database>.createSchemaRequests(adc: AuroraDeploymentSpec): List<SchemaProvisionRequest> {
        return createSchemaRequests(userDetailsProvider, adc)
    }
}

const val databaseDefaultsKey = "databaseDefaults"

abstract class DatabaseFeatureTemplate(val cluster: String) : Feature {

    override fun createContext(
        spec: AuroraDeploymentSpec,
        cmd: AuroraContextCommand,
        validationContext: Boolean
    ): Map<String, Any> {
        return mapOf("databases" to findDatabases(spec))
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> =
        dbHandlers(cmd)
}
