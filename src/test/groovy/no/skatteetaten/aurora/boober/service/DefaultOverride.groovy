package no.skatteetaten.aurora.boober.service

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.TYPE, ElementType.METHOD])
@interface DefaultOverride {
  boolean interactions() default true

  boolean auroraConfig() default true
}