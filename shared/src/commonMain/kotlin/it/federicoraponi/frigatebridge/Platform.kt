package it.federicoraponi.frigatebridge

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
