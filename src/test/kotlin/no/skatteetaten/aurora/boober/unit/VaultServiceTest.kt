package no.skatteetaten.aurora.boober.unit

import assertk.all
import assertk.assertThat
import assertk.assertions.containsAll
import assertk.assertions.hasMessage
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSuccess
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.service.EncryptionService
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.UnauthorizedAccessException
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.vault.VaultService
import no.skatteetaten.aurora.boober.utils.recreateFolder
import no.skatteetaten.aurora.boober.utils.recreateRepo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.io.File

class VaultServiceTest {

    val REMOTE_REPO_FOLDER = File("build/gitrepos_vault_bare").absoluteFile.absolutePath
    val CHECKOUT_PATH = File("build/vaults").absoluteFile.absolutePath

    val auroraMetrics = AuroraMetrics(SimpleMeterRegistry())

    val userDetailsProvider = mockk<UserDetailsProvider>()

    val gitService = GitService(userDetailsProvider, "$REMOTE_REPO_FOLDER/%s", CHECKOUT_PATH, "", "", auroraMetrics)

    val encryptionService = mockk<EncryptionService>()

    val vaultService =
        VaultService(gitService, encryptionService, userDetailsProvider)

    val COLLECTION_NAME = "paas"

    val VAULT_NAME = "test"

    @BeforeEach
    fun setup() {
        clearAllMocks()
        recreateRepo(File(REMOTE_REPO_FOLDER, "$COLLECTION_NAME.git"))

        every {
            userDetailsProvider.getAuthenticatedUser()
        } returns User("aurora", "token", "Aurora Test User", listOf(SimpleGrantedAuthority("UTV")))

        val decrypt = slot<String>()
        val encrypt = slot<ByteArray>()

        every {
            encryptionService.decrypt(capture(decrypt))
        } answers { decrypt.captured.toByteArray() }

        every {
            encryptionService.encrypt(capture(encrypt))
        } answers { String(encrypt.captured) }
    }

    @Test
    fun `Find vault collection`() {

        val vaultCollection = vaultService.findVaultCollection(COLLECTION_NAME)

        assertThat(vaultCollection).isNotNull()
        assertThat(vaultCollection.vaults.size).isEqualTo(0)
    }

    @Test
    fun `Update file`() {

        val fileName = "passwords.properties"
        val contents = "SERVICE_PASSWORD=FOO"

        var vault =
            vaultService.createOrUpdateFileInVault(COLLECTION_NAME, VAULT_NAME, fileName, contents.toByteArray())

        assertThat(vault.secrets.size).isEqualTo(1)
        assertThat(vault.secrets.getValue(fileName)).isEqualTo(contents.toByteArray())

        recreateFolder(File(CHECKOUT_PATH))

        val vaultCollection = vaultService.findVaultCollection(COLLECTION_NAME)
        vault = vaultCollection.findVaultByName(VAULT_NAME)!!

        assertThat(vault.secrets.size).isEqualTo(1)
        assertThat(vault.secrets.getValue(fileName)).isEqualTo(contents.toByteArray())
    }

    @Test
    fun `Secret vault keys must have correct name`() {

        val contents = mapOf("latest.properties" to "INVALID KEY=FOO".toByteArray())

        assertThat {
            vaultService.import(
                vaultCollectionName = COLLECTION_NAME,
                vaultName = VAULT_NAME,
                permissions = listOf(),
                secrets = contents
            )
        }.isFailure().all {
            isInstanceOf(IllegalArgumentException::class)
            hasMessage("Vault key=[latest.properties/INVALID KEY] is not valid. Regex used for matching ^[-._a-zA-Z0-9]+\$")
        }
    }

    @Test
    fun `Verify secret file invalid lines`() {

        val lines = listOf(
            "SOME-KEY = SOME VALUE",
            " SOME-KEY=SOME VALUE",
            " SOME-KEY = SOME VALUE"
        )
        lines.forEach { line ->
            assertThat {
                VaultService.assertSecretKeysAreValid(mapOf("latest.properties" to line.toByteArray()))
            }.isFailure().all { isInstanceOf(IllegalArgumentException::class) }
        }
    }

    @Test
    fun `Verify secret file valid lines`() {
        val lines = listOf(
            "SOME_KEY=SOME VALUE",
            "SOME-KEY=SOME VALUE"
        )
        lines.forEach { line ->
            assertThat {
                        VaultService.assertSecretKeysAreValid(mapOf("latest.properties" to line.toByteArray()))
                    }.isSuccess()
        }
    }

    @Test
    fun `Delete file`() {

        val fileName = "passwords.properties"
        val contents = "SERVICE_PASSWORD=FOO"
        var vault =
            vaultService.createOrUpdateFileInVault(COLLECTION_NAME, VAULT_NAME, fileName, contents.toByteArray())
        vaultService.createOrUpdateFileInVault(
            COLLECTION_NAME,
            VAULT_NAME,
            "passwords2.properties",
            contents.toByteArray()
        )

        assertThat(vault.secrets.size).isEqualTo(2)
        assertThat(vault.secrets.getValue(fileName)).isEqualTo(contents.toByteArray())

        vaultService.deleteFileInVault(COLLECTION_NAME, VAULT_NAME, fileName)

        assertThat(vault.secrets.size).isEqualTo(1)

        recreateFolder(File(CHECKOUT_PATH))

        val vaultCollection = vaultService.findVaultCollection(COLLECTION_NAME)
        vault = vaultCollection.findVaultByName(VAULT_NAME)!!

        assertThat(vault.secrets.size).isEqualTo(1)
    }

    @Test
    fun `Delete vault`() {

        val fileName = "passwords.properties"
        val contents = "SERVICE_PASSWORD=FOO"
        val vault =
            vaultService.createOrUpdateFileInVault(COLLECTION_NAME, VAULT_NAME, fileName, contents.toByteArray())

        assertThat(vault.secrets.size).isEqualTo(1)
        assertThat(vault.secrets.getValue(fileName)).isEqualTo(contents.toByteArray())

        vaultService.deleteVault(COLLECTION_NAME, VAULT_NAME)

        assertThat {
            vaultService.findVault(COLLECTION_NAME, VAULT_NAME)
        }.isFailure().all { isInstanceOf(IllegalArgumentException::class) }

        assertThat {
            vaultService.findVault(COLLECTION_NAME, VAULT_NAME)
        }.isFailure().all { isInstanceOf(IllegalArgumentException::class) }
    }

    @Test
    fun `Updates permissions`() {

        val fileName = "passwords.properties"
        val contents = "SERVICE_PASSWORD=FOO"
        var vault =
            vaultService.createOrUpdateFileInVault(COLLECTION_NAME, VAULT_NAME, fileName, contents.toByteArray())

        assertThat(vault.permissions).isEmpty()
        vaultService.setVaultPermissions(COLLECTION_NAME, VAULT_NAME, listOf("UTV"))
        vault = vaultService.findVault(COLLECTION_NAME, VAULT_NAME)

        assertThat(vault.permissions).isEqualTo(listOf("UTV"))
    }

    @Test
    fun `Get vault when user has no access should throw UnauthorizedAccessException`() {

        val fileName = "passwords.properties"
        val contents = "SERVICE_PASSWORD=FOO"
        vaultService.createOrUpdateFileInVault(COLLECTION_NAME, VAULT_NAME, fileName, contents.toByteArray())
        vaultService.setVaultPermissions(COLLECTION_NAME, VAULT_NAME, listOf())

        val vault = vaultService.findVault(COLLECTION_NAME, VAULT_NAME)
        vault.permissions = listOf("admin")

        assertThat {
            vaultService.findVault(COLLECTION_NAME, VAULT_NAME)
        }.isFailure().all { isInstanceOf(UnauthorizedAccessException::class) }

        val vaults = vaultService.findAllVaultsWithUserAccessInVaultCollection(COLLECTION_NAME)
        val vaultWithAccess = vaults.find { it.vaultName == VAULT_NAME }!!

        assertThat(vaultWithAccess).isNotNull()
        assertThat(vaultWithAccess.hasAccess).isFalse()
        assertThat(vaultWithAccess.vault).isNull()
    }

    @Test
    fun `Set vault permissions when user are not in any group should throw UnauthorizedAccessException`() {
        assertThat {
            vaultService.setVaultPermissions(COLLECTION_NAME, VAULT_NAME, listOf("admin"))
        }.isFailure().all {
            isInstanceOf(UnauthorizedAccessException::class)
            hasMessage("You (aurora) do not have required permissions ([admin]) to operate on this vault. You have [UTV]")
        }
    }

    @Test
    fun `Set vault permissions when user are in one or more groups should update vault permissions`() {
        val fileName = "passwords.properties"
        val contents = "SERVICE_PASSWORD=FOO"
        vaultService.createOrUpdateFileInVault(COLLECTION_NAME, VAULT_NAME, fileName, contents.toByteArray())

        vaultService.setVaultPermissions(COLLECTION_NAME, VAULT_NAME, listOf("admin", "UTV"))

        val vault = vaultService.findVault(COLLECTION_NAME, VAULT_NAME)
        assertThat(vault.permissions).isEqualTo(listOf("admin", "UTV"))
    }

    @Test
    fun `Find secret vault keys`() {
        val fileName = "latest.properties"
        val contents = "key1=foo\nkey2=bar\nkey3=baz"
        vaultService.createOrUpdateFileInVault(COLLECTION_NAME, VAULT_NAME, fileName, contents.toByteArray())
        val vaultKeys = vaultService.findVaultKeys(COLLECTION_NAME, VAULT_NAME, fileName)

        assertThat(vaultKeys.size).isEqualTo(3)
        assertThat(vaultKeys).containsAll("key1", "key2", "key3")
    }
}