package no.skatteetaten.aurora.boober.controller.v1

/*
@AutoConfigureRestDocs
@WebMvcTest(
    value = [ApplicationDeploymentController::class],
    secure = false
)
class ApplicationDeploymentControllerTest(@Autowired private val mockMvc: MockMvc) {

    @MockBean
    private lateinit var applicationDeploymentService: ApplicationDeploymentService

    @MockBean
    private lateinit var auroraDeploymentContextService: AuroraDeploymentContextService
    @MockBean
    private lateinit var auroraConfigService: AuroraConfigService

    @Test
    fun `delete ApplicationRef`() {
        val applicationRef = ApplicationRef("demo-deploy", "test")
        val payload = ApplicationDeploymentPayload(listOf(applicationRef))

        given(
            applicationDeploymentService.executeDelete(payload.applicationRefs)
        ).withContractResponse("applicationdeployment/delete") {
            willReturn(content)
        }

        mockMvc.post(
            path = Path("/v1/applicationdeployment/delete"),
            headers = HttpHeaders().contentTypeJson(),
            body = payload
        ) {
            statusIsOk()
                .responseJsonPath("$.success").isTrue()
                .responseJsonPath("$.items[0].reason").equalsValue("Application was successfully deleted")
                .responseJsonPath("$.items[0].applicationRef").equalsObject(applicationRef)
        }
    }

    /* TODO: Fix
    @Test
    fun `list applicationRef given applicationDeploymentRef`() {
        val adr = ApplicationDeploymentRef("deploy", "reference")
        val payload = ApplicationDeploymentRefPayload(listOf(adr))

        val applicationRef = given(auroraDeploymentContextService.expandDeploymentRefToApplicationRef(any(), any(), any()))
                .withContractResponse("applicationdeployment/applications") {
                    willReturn(content)
                }.mockResponse

        given(applicationDeploymentService.checkApplicationDeploymentsExists(applicationRef))
                .withContractResponse("applicationdeployment/applications_status") {
                    willReturn(content)
                }

        mockMvc.post(
                path = Path("/v1/applicationdeployment/demo?reference=test"),
                headers = HttpHeaders().contentTypeJson(),
                body = payload
        ) {
            statusIsOk()
                    .responseJsonPath("$.success").isTrue()
                    .responseJsonPath("$.items[0].message").equalsValue("Application exists")
                    .responseJsonPath("$.items[0].exists").equalsValue(true)
                    .responseJsonPath("$.items[0].applicationRef").equalsObject(ApplicationRef("demo-deploy", "reference"))
        }
    } */
}
*/
