package no.skatteetaten.aurora.boober.integration

// TODO: Hva er egentlig vits å teste her?

/*
class OpenShiftCommandServiceCreateDeleteCommandsTest {

    val userClient: OpenShiftResourceClient = mockk()

    val openShiftClient = OpenShiftClient(userClient, mockk(), jsonMapper())
    val openShiftCommandBuilder = OpenShiftCommandService(openShiftClient, mockk())

    @BeforeEach
    fun setupTest() {

        clearAllMocks()
        every {
            userClient.getAuthorizationHeaders()
        } returns HttpHeaders()
        Instants.determineNow = { Instant.EPOCH }
    }

    @Test
    fun `Should create delete command for all resources with given deployId`() {

        val name = "aos-simple"
        val namespace = "secretmount"
        val deployId = "abc123"
        val adr = ApplicationDeploymentRef(namespace, name)

        val responses = createResponsesFromResultFiles(adr)

        responses.forEach {
            val kind = it.key
            val queryString = "labelSelector=app=$name,booberDeployId,booberDeployId!=$deployId"
            val apiUrl = OpenShiftResourceClient.generateUrl(kind, namespace)
            val url = "$apiUrl?$queryString"
            every {
                userClient.get(url, any(), true)
            } returns ResponseEntity.ok(it.value)
        }

        val commands = openShiftCommandBuilder.createOpenShiftDeleteCommands(name, namespace, deployId)

        listOf("BuildConfig", "DeploymentConfig", "ConfigMap", "ImageStream", "Service", "Secret").forEach {
            assertThat(containsKind(it, commands)).isTrue()
        }
    }

    fun createResponsesFromResultFiles(adr: ApplicationDeploymentRef): Map<String, JsonNode> {

        return getResultFiles(adr).map {
            val responseBody = jsonMapper().createObjectNode()
            val items = jsonMapper().createArrayNode()

            val kind = it.key.split("/")[0]
            val kindList = it.value?.get("kind")?.textValue() + "List"

            items.add(it.value)
            responseBody.put("kind", kindList)
            responseBody.set("items", items)

            kind to responseBody
        }.toMap()
    }

    fun containsKind(kind: String, commands: List<OpenshiftCommand>): Boolean {
        return commands.any { cmd ->
            cmd.payload.get("kind")?.let {
                it.textValue() == kind
            } ?: false
        }
    }
}
*/
