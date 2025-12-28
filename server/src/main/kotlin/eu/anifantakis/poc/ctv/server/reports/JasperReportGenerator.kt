package eu.anifantakis.poc.ctv.server.reports

import net.sf.jasperreports.engine.*
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource
import net.sf.jasperreports.pdf.JRPdfExporter
import net.sf.jasperreports.export.SimpleExporterInput
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput
import net.sf.jasperreports.pdf.SimplePdfExporterConfiguration
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * JasperReports PDF generator for the server
 */
object JasperReportGenerator {

    /**
     * Generate Program Flow PDF report from request data
     */
    fun generateProgramFlowPdf(request: ProgramFlowReportRequest): ByteArray {
        // Convert DTO to bean format for JasperReports
        val items = convertToReportItems(request)

        // Load and compile the report template
        val jasperPrint = generateJasperPrint(
            items = items,
            title = request.title,
            date = request.date,
            emptyTime = request.emptyTimeIndicator,
            logoPath = request.logoPath
        )

        // Export to PDF bytes
        return exportToPdfBytes(jasperPrint)
    }

    /**
     * Convert the DTO structure to flat items for JasperReports
     */
    private fun convertToReportItems(request: ProgramFlowReportRequest): List<ProgramFlowReportItem> {
        val items = mutableListOf<ProgramFlowReportItem>()

        request.timeSlotGroups.forEach { group ->
            group.items.forEachIndexed { index, item ->
                items.add(
                    ProgramFlowReportItem(
                        timeSlot = group.timeLabel,
                        message = item.message,
                        duration = item.duration,
                        durationSeconds = parseDurationToSeconds(item.duration),
                        program = item.program,
                        notes = item.notes,
                        firstInGroup = index == 0,
                        lastInGroup = index == group.items.lastIndex,
                        groupTotalDuration = group.totalDuration,
                        groupSpotCount = group.spotCount
                    )
                )
            }
        }

        return items
    }

    /**
     * Parse duration string "MM:SS" to seconds
     */
    private fun parseDurationToSeconds(duration: String): Int {
        return try {
            val parts = duration.split(":")
            if (parts.size == 2) {
                parts[0].toInt() * 60 + parts[1].toInt()
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Generate JasperPrint from report data
     */
    private fun generateJasperPrint(
        items: List<ProgramFlowReportItem>,
        title: String,
        date: String,
        emptyTime: String,
        logoPath: String?
    ): JasperPrint {
        // Load the JRXML template
        val templateStream: InputStream = Thread.currentThread().contextClassLoader
            ?.getResourceAsStream("reports/ProgramFlowReport.jrxml")
            ?: javaClass.getResourceAsStream("/reports/ProgramFlowReport.jrxml")
            ?: throw IllegalStateException("Report template not found: reports/ProgramFlowReport.jrxml")

        // JasperReports 7.x compilation
        val content = templateStream.bufferedReader().use { it.readText() }
        val jasperContext = DefaultJasperReportsContext.getInstance()
        val contentStream = java.io.ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
        val jasperDesign = net.sf.jasperreports.engine.xml.JRXmlLoader.load(jasperContext, contentStream)
        val jasperReport = JasperCompileManager.getInstance(jasperContext).compile(jasperDesign)

        // Prepare parameters
        val parameters = mutableMapOf<String, Any?>()
        parameters["REPORT_TITLE"] = title
        parameters["REPORT_DATE"] = formatDateForReport(date)
        parameters["EMPTY_TIME"] = emptyTime
        parameters["LOGO_PATH"] = logoPath

        // Create data source from items
        val dataSource = JRBeanCollectionDataSource(items)

        // Fill the report
        return JasperFillManager.fillReport(jasperReport, parameters, dataSource)
    }

    /**
     * Format ISO date to Greek format
     */
    private fun formatDateForReport(isoDate: String): String {
        return try {
            val parts = isoDate.split("-")
            if (parts.size == 3) {
                val year = parts[0]
                val month = parts[1]
                val day = parts[2]

                val dayOfWeek = java.time.LocalDate.parse(isoDate).dayOfWeek
                val greekDay = when (dayOfWeek) {
                    java.time.DayOfWeek.MONDAY -> "Δευτέρα"
                    java.time.DayOfWeek.TUESDAY -> "Τρίτη"
                    java.time.DayOfWeek.WEDNESDAY -> "Τετάρτη"
                    java.time.DayOfWeek.THURSDAY -> "Πέμπτη"
                    java.time.DayOfWeek.FRIDAY -> "Παρασκευή"
                    java.time.DayOfWeek.SATURDAY -> "Σάββατο"
                    java.time.DayOfWeek.SUNDAY -> "Κυριακή"
                }

                "$greekDay - $day/$month/$year"
            } else {
                isoDate
            }
        } catch (e: Exception) {
            isoDate
        }
    }

    /**
     * Export JasperPrint to PDF bytes
     */
    private fun exportToPdfBytes(jasperPrint: JasperPrint): ByteArray {
        val outputStream = ByteArrayOutputStream()

        val exporter = JRPdfExporter()
        exporter.setExporterInput(SimpleExporterInput(jasperPrint))
        exporter.setExporterOutput(SimpleOutputStreamExporterOutput(outputStream))

        val configuration = SimplePdfExporterConfiguration()
        configuration.isCreatingBatchModeBookmarks = true
        exporter.setConfiguration(configuration)

        exporter.exportReport()

        return outputStream.toByteArray()
    }
}

/**
 * Bean class for JasperReports data source
 * Must have public getters for JasperReports to access fields
 */
data class ProgramFlowReportItem(
    val timeSlot: String,
    val message: String,
    val duration: String,
    val durationSeconds: Int,
    val program: String,
    val notes: String,
    val firstInGroup: Boolean,
    val lastInGroup: Boolean,
    val groupTotalDuration: String,
    val groupSpotCount: Int
)
