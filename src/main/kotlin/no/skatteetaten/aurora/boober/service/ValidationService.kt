package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import org.springframework.stereotype.Service
import javax.validation.Validation


@Service
class ValidationService(/*val openShiftService: OpenShiftService*/) {

    fun assertIsValid(config: AuroraDeploymentConfig/*, token: String*/) {

        val validator = Validation.buildDefaultValidatorFactory().validator

        val auroraDcErrors = validator.validate(config)
        val deployDescriptorErrors = validator.validate(config.deployDescriptor)

        val errors = (auroraDcErrors + deployDescriptorErrors).associateBy({ it.propertyPath.toString() }, { it.message })

        //TODO:validate that all users/groups are actually valid groups/users
/*
        if (config is TemplateProcessingConfig) {

*/
/*
            if (config.templateFile != null && !res.sources.keys.contains(config.templateFile)) {
                errors.add("Template file ${config.templateFile} is missing in sources")
            }
*//*


            if (config.template != null && config.templateFile != null) {
                errors.add("Cannot specify both template and templateFile")
            }
*/
/*

            if (config.template != null && !openShiftService.templateExist(token, config.template)) {
                errors.add("Template ${config.template} does not exist in cluster.")
            }
*//*


        }
*/

        if (errors.isNotEmpty()) {
            throw ValidationException("AOC config contains errors", errors = errors)
        }
    }
}
