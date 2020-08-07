package no.skatteetaten.aurora.boober.service

import com.microsoft.graph.models.extensions.IGraphServiceClient
import com.microsoft.graph.options.QueryOption
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

data class GroupInfo(val id: String, val hasMembers: Boolean)

@Service
class AzureService(val graphClient: IGraphServiceClient) {

    fun resolveGroupName(id: String) = graphClient.groups(id).buildRequest().get().displayName

    fun fetchGroupInfo(name: String): GroupInfo? {
        val groups = graphClient
            .groups()
            .buildRequest(listOf(QueryOption("\$filter", "displayName eq '$name'")))
            .expand("members")
            .get()

        if (groups.currentPage.isNullOrEmpty()) {
            return null
        }
        val group = groups.currentPage.first()

        return GroupInfo(group.id, !group.members.currentPage.isNullOrEmpty())
    }
}
