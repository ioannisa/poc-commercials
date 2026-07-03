package eu.anifantakis.ctv.reports.engine

import eu.anifantakis.ctv.reports.dto.ReportBatchRequest
import eu.anifantakis.ctv.reports.dto.ReportRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import net.sf.jasperreports.engine.DefaultJasperReportsContext
import net.sf.jasperreports.engine.JasperCompileManager
import net.sf.jasperreports.engine.JasperFillManager
import net.sf.jasperreports.engine.JasperPrint
import net.sf.jasperreports.engine.JasperReport
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource
import net.sf.jasperreports.engine.xml.JRXmlLoader
import net.sf.jasperreports.export.SimpleExporterInput
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput
import net.sf.jasperreports.pdf.JRPdfExporter
import net.sf.jasperreports.pdf.SimplePdfExporterConfiguration
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic JasperReports engine, shared by the desktop app (in-process
 * generation) and the Ktor server (generation on behalf of browser clients).
 *
 * One engine serves every report: a [ReportRequest.reportId] resolves to the
 * classpath template `reports/{reportId}.jrxml`, and the template itself
 * drives the typing - each JSON parameter/row value is coerced to the Java
 * class the template declares for it, so no per-report bean classes exist.
 *
 * Compiled templates are cached per report id for the process lifetime.
 * Cache invalidation: none is needed. Templates are classpath resources baked
 * into this module's jar at build time, so their content cannot change while
 * the process is alive; changing a report means rebuilding, which starts a
 * new JVM with an empty cache. If templates ever move to user-editable files
 * on disk, replace this with a cache keyed by (path, lastModified).
 */
object ReportEngine {

    /** Also guards against path traversal: ids are used to build resource paths. */
    private val reportIdFormat = Regex("[A-Za-z0-9_-]+")

    private val compiledReports = ConcurrentHashMap<String, JasperReport>()

    /** Whether a template exists for [reportId] (false for malformed ids). */
    fun hasTemplate(reportId: String): Boolean =
        reportId.matches(reportIdFormat) && classLoader().getResource(templatePath(reportId)) != null

    /**
     * Generate the report and export it to PDF bytes (used by the server).
     */
    fun generatePdf(request: ReportRequest): ByteArray =
        exportToPdfBytes(fill(request))

    /**
     * Generate a batch of reports as ONE PDF, in request order (used by the
     * server; e.g. a month = one daily report after another).
     */
    fun generatePdf(batch: ReportBatchRequest): ByteArray =
        exportToPdfBytes(batch.requests.map(::fill))

    /**
     * Fill the report, returning a [JasperPrint] for further use
     * (PDF export, printing via JasperPrintManager, etc.).
     *
     * Filling from a shared compiled [JasperReport] is thread-safe: the
     * compiled report is read-only during fill.
     */
    fun fill(request: ReportRequest): JasperPrint {
        val report = compiledReports.computeIfAbsent(request.reportId, ::loadAndCompileTemplate)

        val parameterTypes = report.parameters.orEmpty()
            .filterNot { it.isSystemDefined }
            .associate { it.name to it.valueClassName }
        val fieldTypes = report.fields.orEmpty().associate { it.name to it.valueClassName }

        val parameters = coerceAll(request.parameters, parameterTypes, report.name, "parameter")
        val rows: List<Map<String, *>> = request.rows.map { coerceAll(it, fieldTypes, report.name, "field") }

        return JasperFillManager.fillReport(report, parameters, JRMapCollectionDataSource(rows))
    }

    /**
     * Export one or more filled reports to PDF bytes (batch order preserved).
     */
    fun exportToPdfBytes(jasperPrint: JasperPrint): ByteArray = exportToPdfBytes(listOf(jasperPrint))

    fun exportToPdfBytes(jasperPrints: List<JasperPrint>): ByteArray {
        val outputStream = ByteArrayOutputStream()
        exportToPdf(jasperPrints, SimpleOutputStreamExporterOutput(outputStream))
        return outputStream.toByteArray()
    }

    /**
     * Export one or more filled reports to a PDF file (batch order preserved).
     */
    fun exportToPdfFile(jasperPrint: JasperPrint, outputFile: File) =
        exportToPdfFile(listOf(jasperPrint), outputFile)

    fun exportToPdfFile(jasperPrints: List<JasperPrint>, outputFile: File) {
        exportToPdf(jasperPrints, SimpleOutputStreamExporterOutput(outputFile))
    }

    private fun exportToPdf(jasperPrints: List<JasperPrint>, output: SimpleOutputStreamExporterOutput) {
        require(jasperPrints.isNotEmpty()) { "Nothing to export: the report batch is empty" }
        val exporter = JRPdfExporter()
        exporter.setExporterInput(SimpleExporterInput.getInstance(jasperPrints))
        exporter.setExporterOutput(output)

        val configuration = SimplePdfExporterConfiguration()
        configuration.isCreatingBatchModeBookmarks = true
        exporter.setConfiguration(configuration)

        exporter.exportReport()
    }

    private fun templatePath(reportId: String) = "reports/$reportId.jrxml"

    private fun loadAndCompileTemplate(reportId: String): JasperReport {
        require(reportId.matches(reportIdFormat)) { "Invalid report id '$reportId'" }
        val templateStream = classLoader().getResourceAsStream(templatePath(reportId))
            ?: throw IllegalArgumentException(
                "Unknown report '$reportId': no template at ${templatePath(reportId)}"
            )

        val jasperContext = DefaultJasperReportsContext.getInstance()
        val jasperDesign = templateStream.use { JRXmlLoader.load(jasperContext, it) }
        return JasperCompileManager.getInstance(jasperContext).compile(jasperDesign)
    }

    private fun classLoader(): ClassLoader =
        Thread.currentThread().contextClassLoader ?: ReportEngine::class.java.classLoader

    /**
     * Coerce a JSON object's values to the Java classes the template declares.
     * Keys the template does not declare fail fast - a typo'd name would
     * otherwise silently render as blank.
     */
    private fun coerceAll(
        values: Map<String, JsonElement>,
        declaredTypes: Map<String, String>,
        reportName: String,
        kind: String,
    ): MutableMap<String, Any?> {
        val unknown = values.keys - declaredTypes.keys
        require(unknown.isEmpty()) {
            "Report '$reportName' declares no $kind(s) $unknown; declared: ${declaredTypes.keys}"
        }
        return values.entries.associateTo(HashMap()) { (name, value) ->
            name to coerceValue(value, declaredTypes.getValue(name), "$kind '$name'")
        }
    }

    private fun coerceValue(value: JsonElement, className: String, description: String): Any? {
        if (value is JsonNull) return null
        val primitive = value as? JsonPrimitive
            ?: throw IllegalArgumentException("$description must be a JSON primitive, not ${value::class.simpleName}")
        return when (className) {
            "java.lang.String" -> primitive.content
            "java.lang.Integer" -> primitive.content.toInt()
            "java.lang.Long" -> primitive.content.toLong()
            "java.lang.Short" -> primitive.content.toShort()
            "java.lang.Byte" -> primitive.content.toByte()
            "java.lang.Double" -> primitive.content.toDouble()
            "java.lang.Float" -> primitive.content.toFloat()
            "java.lang.Boolean" -> primitive.content.toBooleanStrict()
            "java.math.BigDecimal" -> BigDecimal(primitive.content)
            "java.math.BigInteger" -> BigInteger(primitive.content)
            "java.lang.Object" ->
                primitive.booleanOrNull ?: primitive.longOrNull ?: primitive.doubleOrNull ?: primitive.content
            else -> throw IllegalArgumentException(
                "$description is declared as unsupported type $className in the template; " +
                    "use a string (pre-formatted), number, or boolean class instead"
            )
        }
    }
}
