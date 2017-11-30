package no.skatteetaten.aurora.boober.testapp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.facade.VaultFacade
import no.skatteetaten.aurora.boober.mapper.v1.createAuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.SecretVaultService
import no.skatteetaten.aurora.boober.service.renderJsonForAuroraDeploymentSpecPointers
import org.apache.commons.codec.binary.Base64
import org.eclipse.jgit.api.Git
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Import
import java.io.File


@SpringBootApplication(scanBasePackages = arrayOf("no.skatteetaten.aurora.boober.service"))
@Import(Configuration::class, VaultFacade::class)
class TestApp

/**
 * You will need to override BOOBER_ENCRYPT_KEY for this to work properly
 */
fun main(args: Array<String>) {

    val (affiliation, environment, application) = args

    System.setProperty("spring.main.web-environment", "false")
    val dockerRegistry = "docker-registry.aurora.sits.no:5000"

    val aoConfig = jacksonObjectMapper().readValue(File("${System.getProperty("user.home")}/.ao.json").readText(), Map::class.java)
    val paths: Map<String, String> = aoConfig["checkoutPaths"] as Map<String, String>
    val repoPath = paths[affiliation] as String

    val applicationContext = SpringApplication.run(TestApp::class.java, *args)
    val secretVaultService = applicationContext.getBean(SecretVaultService::class.java)
    val git = Git.open(File(repoPath))

    val vaults = secretVaultService.getVaults(git)

    val auroraConfig = AuroraConfig.fromFolder(repoPath)
    val deploymentSpec = createAuroraDeploymentSpec(auroraConfig, ApplicationId.aid(environment, application), dockerRegistry, vaults = vaults)

    println(renderJsonForAuroraDeploymentSpecPointers(deploymentSpec, false))
    println(String(Base64.decodeBase64(deploymentSpec.volume?.secrets?.get("latest.properties"))))
}