package gov.cdc.prime.router.azure

import com.microsoft.azure.functions.HttpMethod
import com.microsoft.azure.functions.HttpRequestMessage
import com.microsoft.azure.functions.HttpResponseMessage
import com.microsoft.azure.functions.HttpStatus
import com.microsoft.azure.functions.annotation.AuthorizationLevel
import com.microsoft.azure.functions.annotation.FunctionName
import com.microsoft.azure.functions.annotation.HttpTrigger
import com.microsoft.azure.functions.annotation.StorageAccount
import com.okta.jwt.JwtVerifiers
import gov.cdc.prime.router.ReportId
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.templateresolver.StringTemplateResolver
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar

class DownloadFunction {
    val DAYS_TO_SHOW = 7L
    val LOGIN_PAGE = "./assets/csv-download-site/login__inline.html"
    val DOWNLOAD_PAGE = "./assets/csv-download-site/index__inline.html"
    val FILENOTFOUND_PAGE = "./assets/csv-download-site/nosuchfile__inline.html"

    data class TestResult(
        val date: String?,
        val receiver: String?,
        val expires: Long?,
        val total: Any? = null,
        val positive: Any? = null,
        val file: String? = null,
        val format: String? = null
    )

    var orgName = ""
    var userName = ""

    @FunctionName("download")
    @StorageAccount("AzureWebJobsStorage")
    fun run(
        @HttpTrigger(
            name = "req",
            methods = [HttpMethod.GET],
            authLevel = AuthorizationLevel.ANONYMOUS
        ) request: HttpRequestMessage<String?>
    ): HttpResponseMessage {
        if (checkAuthenticated(request)) {
            val file: String = request.queryParameters["file"] ?: ""
            if (file.isBlank())
                return responsePage(request)
            else
                return responseFile(request, file)
        } else {
            return serveAuthenticatePage(request)
        }
    }

    private fun serveAuthenticatePage(request: HttpRequestMessage<String?>): HttpResponseMessage {
        val htmlTemplate = Files.readString(Path.of(LOGIN_PAGE))

        val attr = mapOf(
            "OKTA_baseUrl" to System.getenv("OKTA_baseUrl"),
            "OKTA_clientId" to System.getenv("OKTA_clientId"),
            "OKTA_redirect" to System.getenv("OKTA_redirect")
        )

        val html = getTemplateFromAttributes(htmlTemplate, attr)
        var response = request.createResponseBuilder(HttpStatus.OK)
            .body(html)
            .header("Content-Type", "text/html")
            .build()

        return response
    }

    private fun generateTestResults(headers: List<DatabaseAccess.Header>): List<TestResult> {
        return headers.sortedByDescending {
            it.task.createdAt
        }.map {
            val org = WorkflowEngine().metadata.findOrganization(orgName.replace("_", "-"))
            val svc = WorkflowEngine.metadata.findService(it.task.receiverName)
            TestResult(
                it.task.createdAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                if (svc !== null) svc.description else if (org !== null) org.description else "Unknown",
                DAYS_TO_SHOW - it.task.createdAt.until(OffsetDateTime.now(), ChronoUnit.DAYS),
                it.task.itemCount,
                0,
                it.task.reportId.toString(),
                it.task.bodyFormat
            )
        }
    }

    private fun generateTodaysTestResults(headers: List<DatabaseAccess.Header>): List<TestResult> {
        var filtered = headers.filter { filter(it) }
        return generateTestResults(filtered)
    }

    private fun filter(it: DatabaseAccess.Header): Boolean {
        val now = OffsetDateTime.now()
        return it.task.createdAt.year == now.year && it.task.createdAt.monthValue == now.monthValue && it.task.createdAt.dayOfMonth == now.dayOfMonth
    }

    private fun generatePreviousTestResults(headers: List<DatabaseAccess.Header>): List<TestResult> {
        var filtered = headers.filterNot { filter(it) }
        return generateTestResults(filtered)
    }

    private fun responsePage(request: HttpRequestMessage<String?>, fileNotFound: Boolean = false): HttpResponseMessage {
        val htmlTemplate: String = Files.readString(Path.of(DOWNLOAD_PAGE))
        val headers = DatabaseAccess(dataSource = DatabaseAccess.dataSource).fetchHeaders(OffsetDateTime.now().minusDays(DAYS_TO_SHOW), orgName)

        val org = WorkflowEngine.metadata.findOrganization(orgName.replace('_', '-'))

        val attr = mapOf(
            "description" to if (org !== null) org.description else "",
            "user" to userName,
            "today" to Calendar.getInstance(),
            "todays" to generateTodaysTestResults(headers),
            "previous" to generatePreviousTestResults(headers),
            "days_to_show" to DAYS_TO_SHOW,
            "OKTA_redirect" to System.getenv("OKTA_redirect"),
            "showTables" to !fileNotFound
        )

        val html = getTemplateFromAttributes(htmlTemplate, attr)

        var response = request.createResponseBuilder(HttpStatus.OK)
            .body(html)
            .header("Content-Type", "text/html")
            .build()

        return response
    }

    private fun responseFile(request: HttpRequestMessage<String?>, fileName: String): HttpResponseMessage {
        val header = DatabaseAccess(dataSource = DatabaseAccess.dataSource).fetchHeader(ReportId.fromString(fileName), orgName)
        var response: HttpResponseMessage
        try {
            val body = WorkflowEngine().readBody(header)
            if (body.size <= 0)
                response = responsePage(request, true)
            else {
                response = request
                    .createResponseBuilder(HttpStatus.OK)
                    .header("Content-Type", "text/csv")
                    .header("Content-Disposition", "attachment; filename=test-results.csv")
                    .body(body)
                    .build()
            }
        } catch (ex: Exception) {
            response = request.createResponseBuilder(HttpStatus.NOT_FOUND).build()
        }

        return response
    }

    private fun getTemplateEngine(): TemplateEngine {
        val templateEngine = TemplateEngine()
        val stringTemplateResolver = StringTemplateResolver()
        templateEngine.setTemplateResolver(stringTemplateResolver)
        return templateEngine
    }

    private fun getTemplateFromAttributes(htmlContent: String, attr: Map<String, Any?>): String {
        val templateEngine = getTemplateEngine()
        val context = Context()
        if (!attr.isEmpty())
            attr.forEach { (k, v) -> context.setVariable(k, v) }
        return templateEngine.process(htmlContent, context)
    }

    private fun checkAuthenticated(request: HttpRequestMessage<String?>): Boolean {
        userName = ""
        orgName = ""

        val cookies = request.headers["cookie"] ?: ""
        var jwtString = ""

        cookies.replace(" ", "").split(";").forEach {
            val cookie = it.split("=")
            jwtString = if (cookie[0] == "jwt") cookie[1] else ""
            if (jwtString.isNotBlank()) {
                try {
                    val jwtVerifier = JwtVerifiers.accessTokenVerifierBuilder()
                        .setIssuer("https://${System.getenv("OKTA_baseUrl")}/oauth2/default")
                        .build()
                    val jwt = jwtVerifier.decode(jwtString)
                    userName = jwt.getClaims().get("sub").toString()
                    val orgs = jwt.getClaims().get("organization")
                    var org = if (orgs !== null) (orgs as List<String>)[0] else ""

                    orgName = if (org.length > 3) org.substring(2) else ""
                } catch (ex: Throwable) {
                    System.out.println(ex)
                }
            }
        }
        return userName.isNotBlank() && orgName.isNotBlank()
    }
}