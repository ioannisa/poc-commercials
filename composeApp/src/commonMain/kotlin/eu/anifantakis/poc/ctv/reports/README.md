# JasperReports Integration Guide

## Overview

This module provides PDF report generation using JasperReports. JasperReports is a powerful Java-based reporting library that allows creating professional reports from visual templates (JRXML files).

## Platform Support

| Platform | JasperReports Support | Alternative |
|----------|----------------------|-------------|
| **Desktop (JVM)** | ✅ Native support | - |
| **Web (JS/WASM)** | ❌ Not available | Server-side API |
| **Android** | ❌ Not available | Server-side API |
| **iOS** | ❌ Not available | Server-side API |

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         Common Module                            │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐ │
│  │  ReportModels   │  │ ReportService   │  │ ReportDataFactory│ │
│  │  (Data classes) │  │ (expect/actual) │  │ (Data creation)  │ │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘ │
├─────────────────────────────────────────────────────────────────┤
│  JVM (Desktop)        │  Web/Android/iOS                        │
│  ┌─────────────────┐  │  ┌─────────────────────────────────┐   │
│  │ ReportService   │  │  │ ReportService (stub)            │   │
│  │ .jvm.kt         │  │  │ Returns "use server API"        │   │
│  │ - JasperReports │  │  │                                 │   │
│  │ - PDF export    │  │  │ Server-Side Rendering:          │   │
│  │ - File dialogs  │  │  │ POST /api/reports/program-flow  │   │
│  └─────────────────┘  │  └─────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

## Using the Report Toolbar

Add the `ReportToolbar` composable to your screen:

```kotlin
import eu.anifantakis.poc.ctv.reports.ui.ReportToolbar

@Composable
fun TimetableScreen(...) {
    Column {
        // Add report toolbar in your header
        ReportToolbar(
            selectedDate = currentDate,
            breaks = breaks,
            cellData = cellData
        )

        // Rest of your screen...
    }
}
```

The toolbar automatically:
- Detects if JasperReports is available
- Shows enabled buttons on Desktop (JVM)
- Shows disabled buttons with explanation on other platforms

## Creating JRXML Templates

Templates are created using **Jaspersoft Studio** (free, Eclipse-based IDE):

1. Download from: https://community.jaspersoft.com/project/jaspersoft-studio
2. Create new report → Select template or blank
3. Design visually with drag-and-drop
4. Save as `.jrxml` file
5. Place in `src/jvmMain/resources/reports/`

### Template Structure

```xml
<?xml version="1.0" encoding="UTF-8"?>
<jasperReport ...>
    <!-- Parameters (passed from code) -->
    <parameter name="REPORT_TITLE" class="java.lang.String"/>

    <!-- Fields (from data source beans) -->
    <field name="message" class="java.lang.String"/>
    <field name="duration" class="java.lang.String"/>

    <!-- Groups (for subtotals) -->
    <group name="TimeSlotGroup">
        <groupExpression><![CDATA[$F{timeSlot}]]></groupExpression>
        <groupFooter>...</groupFooter>
    </group>

    <!-- Report sections -->
    <title>...</title>
    <columnHeader>...</columnHeader>
    <detail>...</detail>
    <pageFooter>...</pageFooter>
</jasperReport>
```

---

# Server-Side Rendering for Web/Mobile

For Web, Android, and iOS platforms, implement a backend API that generates reports server-side.

## Option 1: Ktor Backend (Kotlin)

Create a Ktor server that uses JasperReports:

### 1. Create Backend Module

```kotlin
// build.gradle.kts (backend module)
dependencies {
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-netty:2.3.7")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")

    // JasperReports
    implementation("net.sf.jasperreports:jasperreports:6.20.6")
    implementation("net.sf.jasperreports:jasperreports-fonts:6.20.6")
}
```

### 2. Create Report Endpoint

```kotlin
// ReportRoutes.kt
fun Route.reportRoutes() {
    post("/api/reports/program-flow") {
        val request = call.receive<ProgramFlowRequest>()

        // Generate report using JasperReports
        val pdfBytes = ReportGenerator.generateProgramFlowPdf(
            date = request.date,
            items = request.items,
            emptyTime = request.emptyTime
        )

        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"ProgramFlow_${request.date}.pdf\""
        )
        call.respondBytes(pdfBytes, ContentType.Application.Pdf)
    }
}

// ReportGenerator.kt
object ReportGenerator {
    fun generateProgramFlowPdf(
        date: String,
        items: List<ProgramFlowItem>,
        emptyTime: String
    ): ByteArray {
        val templateStream = javaClass.getResourceAsStream("/reports/ProgramFlowReport.jrxml")
        val jasperReport = JasperCompileManager.compileReport(templateStream)

        val parameters = mapOf(
            "REPORT_TITLE" to "ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ",
            "REPORT_DATE" to date,
            "EMPTY_TIME" to emptyTime
        )

        val dataSource = JRBeanCollectionDataSource(items)
        val jasperPrint = JasperFillManager.fillReport(jasperReport, parameters, dataSource)

        return ByteArrayOutputStream().use { os ->
            JasperExportManager.exportReportToPdfStream(jasperPrint, os)
            os.toByteArray()
        }
    }
}
```

### 3. Client-Side Integration (Web/Mobile)

Update the platform-specific `ReportService` to call the API:

```kotlin
// ReportService.js.kt or ReportService.wasmJs.kt
actual class ReportService actual constructor() {
    private val httpClient = HttpClient(Js) {
        install(ContentNegotiation) {
            json()
        }
    }

    actual suspend fun exportProgramFlowToPdf(
        reportData: ProgramFlowReportData,
        config: ReportConfig,
        suggestedFileName: String
    ): ReportResult {
        return try {
            val response = httpClient.post("https://your-server.com/api/reports/program-flow") {
                contentType(ContentType.Application.Json)
                setBody(ProgramFlowRequest(
                    date = reportData.dateFormatted,
                    items = reportData.items,
                    emptyTime = reportData.emptyTimeFormatted
                ))
            }

            // Trigger browser download
            val blob = response.body<ByteArray>()
            downloadBlob(blob, suggestedFileName)

            ReportResult.Success("PDF downloaded")
        } catch (e: Exception) {
            ReportResult.Error("Failed to generate PDF: ${e.message}", e)
        }
    }
}

// Browser download helper
external fun downloadBlob(data: ByteArray, filename: String)
```

## Option 2: Spring Boot Backend (Java/Kotlin)

```kotlin
// ReportController.kt
@RestController
@RequestMapping("/api/reports")
class ReportController(
    private val reportService: JasperReportService
) {
    @PostMapping("/program-flow", produces = [MediaType.APPLICATION_PDF_VALUE])
    fun generateProgramFlow(
        @RequestBody request: ProgramFlowRequest
    ): ResponseEntity<ByteArray> {
        val pdfBytes = reportService.generateProgramFlow(request)

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"ProgramFlow_${request.date}.pdf\"")
            .body(pdfBytes)
    }
}
```

## Option 3: Serverless (AWS Lambda / Google Cloud Functions)

Deploy JasperReports as a serverless function:

```kotlin
// AWS Lambda Handler
class ReportHandler : RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    override fun handleRequest(
        input: APIGatewayProxyRequestEvent,
        context: Context
    ): APIGatewayProxyResponseEvent {
        val request = Json.decodeFromString<ProgramFlowRequest>(input.body)
        val pdfBytes = generateReport(request)

        return APIGatewayProxyResponseEvent()
            .withStatusCode(200)
            .withHeaders(mapOf(
                "Content-Type" to "application/pdf",
                "Content-Disposition" to "attachment; filename=\"report.pdf\""
            ))
            .withBody(Base64.getEncoder().encodeToString(pdfBytes))
            .withIsBase64Encoded(true)
    }
}
```

---

## API Request/Response Format

### Request
```json
POST /api/reports/program-flow
Content-Type: application/json

{
  "date": "Δευτέρα - 28/12/2025",
  "emptyTime": "Κενός Χρόνος: 05:30",
  "items": [
    {
      "timeSlot": "08:00",
      "message": "ΚΟΙΝΩΝΙΚΑ ΠΕΛΑΤΕΣ ΔΙΑΦΟΡΟΙ",
      "duration": "00:30",
      "durationSeconds": 30,
      "program": "SPOT",
      "notes": "ΡΟΗ",
      "isFirstInGroup": true,
      "isLastInGroup": false,
      "groupTotalDuration": "02:30",
      "groupSpotCount": 5
    }
  ]
}
```

### Response
```
HTTP/1.1 200 OK
Content-Type: application/pdf
Content-Disposition: attachment; filename="ProgramFlow_2025-12-28.pdf"

[Binary PDF data]
```

---

## Deployment Considerations

1. **Font Support**: Ensure DejaVu Sans fonts are available for Greek characters
2. **Memory**: JasperReports can be memory-intensive for large reports
3. **Caching**: Consider caching compiled `.jasper` files
4. **Timeout**: Set appropriate timeouts for large reports

## Alternative Libraries

If JasperReports doesn't fit your needs:

| Library | Pros | Cons |
|---------|------|------|
| **iText** | Pure PDF, lightweight | No visual designer |
| **Apache PDFBox** | Java native | Manual layout |
| **Flying Saucer** | HTML to PDF | Limited CSS support |
| **Carbone** | Office templates | External service |
