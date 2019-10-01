package no.skatteetaten.aurora.boober.unit

class DeployServiceProjectTerminatingTest {
    /* TODO: Fix
    val ENV_NAME = "booberdev"
    val APP_NAME = "aos-simple"
    val ref = AuroraConfigRef("aos", "master", "123")

    val aid = ApplicationDeploymentRef(ENV_NAME, APP_NAME)

    val openShiftClient: OpenShiftClient = mockk()
    val userDetailsProvider: UserDetailsProvider = mockk()

    val deployService = DeployService(
        auroraConfigService = mockk(),
        openShiftCommandBuilder = mockk(),
        openShiftClient = openShiftClient,
        dockerService = mockk(),
        redeployService = mockk(),
        userDetailsProvider = userDetailsProvider,
        deployLogService = mockk(),
        cluster = "utv",
        dockerRegistry = "docker.registry"
    )

    val group = "APP_demo_drift"

    @BeforeEach
    fun setup() {
        every { userDetailsProvider.getAuthenticatedUser() } returns User(
            username = "aurora",
            token = "token",
            fullName = "Aurora OpenShift",
            grantedAuthorities = listOf(SimpleGrantedAuthority(group))
        )
        every { openShiftClient.projectExists(any()) } throws IllegalStateException("Project is terminating")
    }

    @Test
    fun `Should return with error if project is terminating`() {

        val env = AuroraDeployEnvironment(
            affiliation = "demo",
            envName = "foo",
            permissions = Permissions(
                admin = Permission(setOf(group))
            ), ttl = null
        )

        assertThat {
            deployService.prepareDeployEnvironment(env)
        }.isFailure().all {
            isInstanceOf(IllegalStateException::class)
        }
    }

     */
}
