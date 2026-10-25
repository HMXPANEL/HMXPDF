package com.hmx.pdf.data.report

const val TEST_NOW = 1_700_000_000_000L
const val TEST_CLIENT_ID = "11111111-1111-4111-8111-111111111111"
const val TEST_INSTALL_ID = "22222222-2222-4222-8222-222222222222"
const val TEST_SERVER_ID = "33333333-3333-4333-8333-333333333333"

fun testApp(
    versionCode: Int = 6,
    versionName: String = "2.1.1",
    flavor: BuildFlavor = BuildFlavor.PLAY,
    buildType: BuildType = BuildType.RELEASE,
    androidApi: Int? = 35,
    locale: String? = "en-US",
) = ReportApp(
    versionCode = versionCode,
    versionName = versionName,
    flavor = flavor,
    buildType = buildType,
    androidApi = androidApi,
    locale = locale,
)

fun testDiagnostic(
    clientReportId: String = TEST_CLIENT_ID,
    installId: String = TEST_INSTALL_ID,
    nowMillis: Long = TEST_NOW,
    app: ReportApp = testApp(),
) = ReportPayloadFactory.diagnostic(
    clientReportId = clientReportId,
    installId = installId,
    clientCreatedAt = ReportTimestamps.iso8601Utc(nowMillis),
    app = app,
    selfChecks = mapOf(
        SelfCheckKey.STORAGE_WRITE to SelfCheckValue.OK,
        SelfCheckKey.CACHE_WRITE to SelfCheckValue.OK,
        SelfCheckKey.PDF_ENGINE_INIT to SelfCheckValue.OK,
        SelfCheckKey.REPORT_QUEUE_INIT to SelfCheckValue.OK,
    ),
    nowMillis = nowMillis,
)

fun testProblem(
    clientReportId: String = TEST_CLIENT_ID,
    installId: String = TEST_INSTALL_ID,
    description: String = "The export button did nothing.",
    nowMillis: Long = TEST_NOW,
    app: ReportApp = testApp(),
) = ReportPayloadFactory.problem(
    clientReportId = clientReportId,
    installId = installId,
    clientCreatedAt = ReportTimestamps.iso8601Utc(nowMillis),
    app = app,
    description = ReportSanitizer.description(description),
    nowMillis = nowMillis,
)
