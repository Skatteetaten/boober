package no.skatteetaten.aurora.boober.utils

import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata

@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FILE,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER
)
@Retention(RetentionPolicy.RUNTIME)
@Conditional(ConditionalOnPropertyMissingOrEmpty.OnPropertyNotEmptyCondition::class)
annotation class ConditionalOnPropertyMissingOrEmpty(val value: String) {

    class OnPropertyNotEmptyCondition : Condition {

        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {

            val property: String? = metadata.getAnnotationAttributes(ConditionalOnPropertyMissingOrEmpty::class.java.name)
                ?.let {
                    it["value"] as String?
                }?.let {
                    context.environment.getProperty(it)
                }

            return property.isNullOrEmpty()
        }
    }
}
