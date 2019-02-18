package no.skatteetaten.aurora.boober.utils

import io.fabric8.openshift.api.model.ImageStream

fun ImageStream.findCurrentImageHash(tagName: String): String? =
    this.status?.tags?.firstOrNull { it.tag == tagName }?.items?.firstOrNull()?.image

fun ImageStream.findDockerImageUrl(tagName: String): String? =
    this.spec?.tags?.firstOrNull { it.name == tagName }?.from?.name