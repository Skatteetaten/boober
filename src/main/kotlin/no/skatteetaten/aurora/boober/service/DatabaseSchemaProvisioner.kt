package no.skatteetaten.aurora.boober.service

import org.springframework.stereotype.Service

data class SchemaProvisionRequest(val affiliation: String)

data class SchemaProvisionResult(val success: Boolean)

@Service
class DatabaseSchemaProvisioner {
    fun provisionSchemas(schemaProvisionRequests: List<SchemaProvisionRequest>): SchemaProvisionResult {
        return SchemaProvisionResult(false)
    }
}