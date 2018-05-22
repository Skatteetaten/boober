@file:JvmName("ObjectMapperConfigurer")

package no.skatteetaten.aurora.boober

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

fun configureObjectMapper(objectMapper: ObjectMapper): ObjectMapper {
    return objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerModules(JavaTimeModule())
        .registerKotlinModule()
}