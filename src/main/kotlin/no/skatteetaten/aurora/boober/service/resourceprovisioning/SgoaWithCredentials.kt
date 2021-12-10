package no.skatteetaten.aurora.boober.service.resourceprovisioning

import no.skatteetaten.aurora.boober.model.openshift.StorageGridObjectArea

data class SgoaWithCredentials(val sgoas: List<StorageGridObjectArea>, val credentials: List<SgRequestsWithCredentials>)
