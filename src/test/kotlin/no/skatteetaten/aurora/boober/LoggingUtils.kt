package no.skatteetaten.aurora.boober

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.slf4j.LoggerFactory

fun setLogLevels() {
    val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    root.level = Level.WARN
    (LoggerFactory.getLogger("ske") as Logger).level = Level.DEBUG
}