package no.skatteetaten.aurora.boober


import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.io.File

@RestController
class BooberController(val gitService: GitService, val booberConfigService: BooberConfigService) {

    @GetMapping("/setup/{token}/{affiliation}/{env}/{app}")
    fun setup(@PathVariable token: String,
              @PathVariable affiliation: String,
              @PathVariable env: String,
              @PathVariable app: String):BooberResult {

        val dir = File("/tmp/$token/$affiliation")

        val git = gitService.get(dir)

        val files =listOf("about.json", "$env/about.json", "$app.json", "$env/$app.json")

        return booberConfigService.createBooberResult(dir, files)
    }
}
