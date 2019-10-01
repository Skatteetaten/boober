package no.skatteetaten.aurora.boober.unit

import no.skatteetaten.aurora.boober.utils.AbstractAuroraConfigTest

class ResourceMergerTest : AbstractAuroraConfigTest() {

    /* TODO: Reimplement
    val ENVIRONMENT = "utv"

    val auroraConfigJson = mutableMapOf(
        "about.json" to DEFAULT_ABOUT,
        "utv/about.json" to DEFAULT_UTV_ABOUT,
        "webleveranse.json" to WEB_LEVERANSE,
        "utv/webleveranse.json" to """{ "type" : "development", "version" : "dev-SNAPSHOT" }"""
    )

    val userDetailsProvider = mockk<UserDetailsProvider>()


    @BeforeEach
    fun setup() {
        clearAllMocks()
        every { userDetailsProvider.getAuthenticatedUser() } returns User("username", "token")
    }

    @Test
    fun `Merge DeploymentConfig`() {

        val existing = loadJsonResource("dc-webleveranse.json")


        val merged = mergeWithExistingResource(newResource, existing)

        val lastTriggeredImagePath = "/spec/triggers/0/imageChangeParams/lastTriggeredImage"

        assertThat(newResource.at(lastTriggeredImagePath).isMissingNode).isTrue()
        assertThat(existing.at(lastTriggeredImagePath).textValue()).isNotNull()
        assertThat(merged.at(lastTriggeredImagePath).textValue()).isEqualTo(existing.at(lastTriggeredImagePath).textValue())

        listOf(0, 1).forEach {
            val imagePath = "/spec/template/spec/containers/$it/image"
            assertThat(newResource.at(imagePath).isMissingNode).isTrue()
            assertThat(existing.at(imagePath).textValue()).isNotNull()
            assertThat(merged.at(imagePath).textValue()).isEqualTo(existing.at(imagePath).textValue())
        }
    }

    @Test
    fun `Merge namespace`() {
        val existing = loadJsonResource("namespace-aos-utv.json")
        val newResource = objectGenerator.generateNamespace(deploymentSpec.environment)
        val merged = mergeWithExistingResource(newResource, existing)
        assertThat(existing.at("/metadata/annotations")).isEqualTo(merged.at("/metadata/annotations"))
    }

    enum class OpenShiftResourceTypeTestData(
        val fields: List<String>
    ) {

        SERVICE(listOf("/metadata/resourceVersion", "/spec/clusterIP")),
        DEPLOYMENTCONFIG(listOf("/metadata/resourceVersion", "/spec/template/spec/containers/0/image")),
        BUILDCONFIG(
            listOf(
                "/metadata/resourceVersion",
                "/spec/triggers/0/imageChange/lastTriggeredImageID",
                "/spec/triggers/1/imageChange/lastTriggeredImageID"
            )
        ),
        CONFIGMAP(listOf("/metadata/resourceVersion"))
    }

    @ParameterizedTest
    @EnumSource(OpenShiftResourceTypeTestData::class)
    fun `Should update`(test: OpenShiftResourceTypeTestData) {

        val type = test.name.toLowerCase()
        val oldResource = loadJsonResource("openshift-objects/$type.json")
        val newResource = loadJsonResource("openshift-objects/$type-new.json")
        val merged = mergeWithExistingResource(newResource, oldResource)
        test.fields.forEach {
            assertThat(merged.at(it)).isEqualTo(oldResource.at(it))
        }
    }

     */
}
