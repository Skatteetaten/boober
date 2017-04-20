package no.skatteetaten.aurora.boober.controller


import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.controller.SetupCommand
import no.skatteetaten.aurora.boober.service.ApplicationResult
import no.skatteetaten.aurora.boober.service.FileService
import no.skatteetaten.aurora.boober.service.SetupService
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.io.File

@RestController
class SetupController(val setupService: SetupService, val fileService: FileService) {

    @PutMapping("/deploy")
    fun deploy(@RequestBody cmd: SetupCommand): Response {

        val dir = File("") //TODO this must be dir from git

        val auroraConfig = AuroraConfig(fileService.findFiles(dir), fileService.findAndDecryptSecretV1(dir))
        val applicationResults: List<ApplicationResult> = setupService.executeSetup(auroraConfig, cmd.envs, cmd.apps)
        return Response(items = applicationResults)
    }

    @PutMapping("/setup")
    fun setup(@RequestBody cmd: SetupCommand): Response {

        return executeSetup(cmd)
    }


    @PutMapping("/setup-dryrun")
    fun setupDryRun(@RequestBody cmd: SetupCommand): Response {

        return executeSetup(cmd, true)
    }

    private fun executeSetup(cmd: SetupCommand, dryRun: Boolean = false): Response {

        val auroraConfig = AuroraConfig(cmd.files, cmd.secretFiles)
        val applicationResults: List<ApplicationResult> = setupService.executeSetup(auroraConfig, cmd.envs, cmd.apps)
        return Response(items = applicationResults)
    }
}
