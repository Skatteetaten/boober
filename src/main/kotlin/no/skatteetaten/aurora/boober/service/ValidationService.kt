package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.Result
import no.skatteetaten.aurora.boober.model.TemplateType
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.regex.Pattern


@Service
class ValidationService(val openshiftService: OpenshiftService) {

    fun validate(res: Result, token: String): Result {

        val config = res.config
        if (config == null) {
            return res
        }

        val errors: MutableList<String> = mutableListOf()


        val nameRe = "^[a-z][-a-z0-9]{0,23}[a-z0-9]$"
        val affiliationRe = "^[a-z]{0,23}[a-z]$"
        val namespaceRe = "^[a-z0-9][-a-z0-9]*[a-z0-9]$"

        if (!Pattern.matches(nameRe, config.name)) {
            errors.add("Name is not a valid DNS952 label $nameRe")

        }
        if (!Pattern.matches(affiliationRe, config.affiliation)) {
            errors.add("Affiliation is not valid $affiliationRe")

        }

        if (!Pattern.matches(namespaceRe, config.namespace)) {
            errors.add("Namespace is not valid $namespaceRe")
        }

        if (config.type == TemplateType.process) {

            if (config.templateFile != null && !res.sources.keys.contains(config.templateFile)) {
                errors.add("Template file ${config.templateFile} is missing in sources")
            }

            if (config.deploy != null) {
                errors.add("Deploy parameters are not viable for process type")
            }

            if(config.template !=null && config.templateFile != null) {
                errors.add("Cannot specify both template and templateFile")

            }

            if (config.template != null && !openshiftService.templateExist(token, config.template)) {
                errors.add("Template ${config.template} does not exist in cluster.")
            }

        }

        return res.copy(errors = res.errors.plus(errors))
    }
}

@Service
class OpenshiftService(@Value("\${openshift.url}") val url: String) {

    fun templateExist(token: String, template: String): Boolean {
        //TODO GET request to openshift with token to check if template exist in Openshift namespace
        return true

    }

}
