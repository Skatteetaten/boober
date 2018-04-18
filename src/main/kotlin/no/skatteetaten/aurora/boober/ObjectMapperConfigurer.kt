@file:JvmName("ObjectMapperConfigurer")

package no.skatteetaten.aurora.boober

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

fun configureObjectMapper(objectMapper: ObjectMapper): ObjectMapper {
    return objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.INDENT_OUTPUT, true)
            .registerModules(JavaTimeModule())
            .registerKotlinModule()
}