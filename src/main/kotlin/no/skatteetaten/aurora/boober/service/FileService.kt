package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.treeToValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File


@Service
class FileService(val mapper: ObjectMapper, val encryptionService: EncryptionService) {

    val logger: Logger = LoggerFactory.getLogger(FileService::class.java)

    fun findAndDecryptSecretV1(basedir: File): Map<String, String> {
        val dir = File(basedir, ".secretv1")
        return dir.walkBottomUp()
                .filter { it.isFile }
                .associate { it.relativeTo(dir).path to encryptionService.decrypt(it) }
    }

    fun findFiles(dir: File): Map<String, Map<String, Any?>> {

        fun readFile(file: File): Map<String, Any?> {
            return mapper.treeToValue(mapper.readTree(file))
        }
        return dir.walkBottomUp()
                .onEnter { !it.name.startsWith(".secret") && !it.name.startsWith(".git") }
                .filter { it.isFile }
                .associate { it.relativeTo(dir).path to readFile(it) }
    }


}
