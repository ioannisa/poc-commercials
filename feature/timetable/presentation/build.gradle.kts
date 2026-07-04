plugins {
    id("commercials.kmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.timetable.domain)
            api(projects.core.presentation)

            // Core-role UI modules (user decision): the scheduler grid
            // engine and the report toolbar/printing service.
            api(projects.grids)
            implementation(projects.reportsClient)
        }
    }
}
