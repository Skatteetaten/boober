package no.skatteetaten.aurora.boober.service.openshift

import org.springframework.stereotype.Component
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException

@Component
class LocalKubeConfigTokenProvider() : TokenProvider {
    override fun getToken(): String {
        val userHome = System.getProperty("user.home")
        val path = File("$userHome/.kube/config")
        return getTokenFromKubeConfig(path)
    }

    fun getTokenFromKubeConfig(path: File): String {
        return KubeConfig.create(path).token
    }
}

private class KubeConfig private constructor(config: Map<String, Any>) {

    var config: Map<String, Any> = config
        private set

    val token: String
        get() {
            val users = getFromMap<List<Map<String, Any>>>(config, "users")
            val user = users.stream()
                    .filter { u -> u["name"] == username + "/" + server }
                    .findFirst()
                    .get()

            val userProps = getFromMap<Map<String, String>>(user, "user")

            return userProps["token"] ?: throw IllegalStateException("Unable to find token in provided kube config file")
        }

    private val server: String
        get() {

            val currentContext = KubeConfig.getFromMap<String>(config, "current-context")
            return currentContext.split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[1]
        }

    private val username: String
        get() {
            val currentContext = KubeConfig.getFromMap<String>(config, "current-context")
            return currentContext.split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[2]
        }

    companion object {

        @JvmStatic
        fun create(kubeConfigFile: File): KubeConfig {
            val kubeConfig = try {
                val yamlConfigFile = FileInputStream(kubeConfigFile)
                Yaml().load(yamlConfigFile) as Map<String, Any>
            } catch (ex: FileNotFoundException) {
                throw IllegalStateException("Config file not found in path: " + kubeConfigFile)
            }
            return KubeConfig(kubeConfig)
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> getFromMap(map: Map<String, *>, key: String): T {

            return map[key] as T
        }
    }
}
