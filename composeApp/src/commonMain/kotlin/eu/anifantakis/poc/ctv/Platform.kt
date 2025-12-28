package eu.anifantakis.poc.ctv

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform