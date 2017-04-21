package no.skatteetaten.aurora.boober.controller

import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.controller.SetupCommand
import no.skatteetaten.aurora.boober.service.GitService
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auroraconfig")
class AuroraConfigController(val gitService: GitService) {

    @PutMapping("/")
    fun gitSave(@RequestBody cmd: SetupCommand) {

        val auroraConfig = AuroraConfig(cmd.files, cmd.secretFiles)
        gitService.saveFiles(cmd.affiliation, auroraConfig)
    }
}


