package no.skatteetaten.aurora.boober.mapper.v1

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.required
import no.skatteetaten.aurora.boober.utils.startsWith

class AuroraVolumeMapperV1(val applicationFiles: List<AuroraConfigFile>,
                           val vaults: Map<String, AuroraSecretVault>) {



    val mountHandlers = findMounts()
    val configHandlers = findConfigFieldHandlers()
    val routeHandlers = findRouteHandlers()

    val handlers = routeHandlers + configHandlers + mountHandlers + listOf(
            AuroraConfigFieldHandler("secretVault", validator = validateSecrets())
    )


    fun auroraDeploymentCore(auroraConfigFields: AuroraConfigFields): AuroraVolume {

        val name = auroraConfigFields.extract("name")

        return AuroraVolume(
                secrets = auroraConfigFields.extractOrNull("secretVault", {
                    vaults[it.asText()]?.secrets
                }),
                config = auroraConfigFields.getConfigMap(configHandlers),
                route = getRoute(auroraConfigFields, name),
                mounts = auroraConfigFields.getMounts(mountHandlers, vaults)
        )
    }

    fun getRoute(auroraConfigFields: AuroraConfigFields, name: String): List<Route> {

        val routes = findSubKeys("route")

        return routes.map {
            val routePath = auroraConfigFields.extractOrNull("route/$it/path")
            val routeHost = auroraConfigFields.extractOrNull("route/$it/host")
            val routeName = if (!it.startsWith(name)) {
                "$name-$it"
            } else {
                it
            }
            Route(routeName, routeHost, routePath, auroraConfigFields.getRouteAnnotations("route/$it/annotations", routeHandlers))
        }.toList()
    }

    private fun validateSecrets(): (JsonNode?) -> Exception? {
        return { json ->

            val secretVault = json?.textValue()
            val secrets = secretVault?.let {
                vaults[it]?.secrets
            }

            if (secretVault != null && (secrets == null || secrets.isEmpty())) {
                IllegalArgumentException("No secret vault named=$secretVault, or you do not have permission to use it.")
            } else {
                null
            }
        }
    }

    fun findRouteHandlers(): List<AuroraConfigFieldHandler> {

        val routeHandlers = findSubKeys("route")

        return routeHandlers.flatMap { routeName ->
            listOf(
                    AuroraConfigFieldHandler("route/$routeName/host"),
                    AuroraConfigFieldHandler("route/$routeName/path", validator = { it?.startsWith("/", "Path must start with /") })
            ) + findRouteAnnotaionHandlers("route/$routeName")
        }
    }

    fun findRouteAnnotaionHandlers(prefix: String): List<AuroraConfigFieldHandler> {

        return applicationFiles.flatMap { ac ->
            ac.contents.at("/$prefix/annotations")?.fieldNames()?.asSequence()?.toList() ?: emptyList()
        }.toSet().map { key ->
            AuroraConfigFieldHandler("$prefix/annotations/$key")
        }
    }


    fun findConfigFieldHandlers(): List<AuroraConfigFieldHandler> {

        val name = "config"
        val keysStartingWithConfig = findSubKeys(name)

        val configKeys: Map<String, Set<String>> = keysStartingWithConfig.map { configFileName ->
            //find all unique keys in a configFile
            val keys = applicationFiles.flatMap { ac ->
                ac.contents.at("/$name/$configFileName")?.fieldNames()?.asSequence()?.toList() ?: emptyList()
            }.toSet()

            configFileName to keys
        }.toMap()

        return configKeys.flatMap { configFile ->
            val value = configFile.value
            if (value.isEmpty()) {
                listOf(AuroraConfigFieldHandler("$name/${configFile.key}"))
            } else {
                value.map { field ->
                    AuroraConfigFieldHandler("$name/${configFile.key}/$field")
                }
            }
        }
    }

    fun findMounts(): List<AuroraConfigFieldHandler> {

        val mountKeys = findSubKeys("mounts")

        return mountKeys.flatMap { mountName ->
            listOf(
                    AuroraConfigFieldHandler("mounts/$mountName/path", validator = { it.required("Path is required for mount") }),
                    AuroraConfigFieldHandler("mounts/$mountName/type", validator = { it.oneOf(MountType.values().map { it.name }) }),
                    AuroraConfigFieldHandler("mounts/$mountName/mountName", defaultValue = mountName),
                    AuroraConfigFieldHandler("mounts/$mountName/volumeName", defaultValue = mountName),
                    AuroraConfigFieldHandler("mounts/$mountName/exist", defaultValue = "false"),
                    AuroraConfigFieldHandler("mounts/$mountName/content"),
                    AuroraConfigFieldHandler("mounts/$mountName/secretVault")
            )

        }
    }

    private fun findSubKeys(name: String): Set<String> {

        return applicationFiles.flatMap {
            if (it.contents.has(name)) {
                it.contents[name].fieldNames().asSequence().toList()
            } else {
                emptyList()
            }
        }.toSet()
    }


}