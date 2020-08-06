package no.skatteetaten.aurora.boober.service

import com.microsoft.graph.models.extensions.IGraphServiceClient
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger= KotlinLogging.logger{}
@Service
class AzureService(val graphClient: IGraphServiceClient) {

    fun resolveGroupName(id: String): String {
        val group=graphClient
            .groups(id)
            .buildRequest()
            .get()
        logger.debug("{}", group)

        return group.displayName
    }
}