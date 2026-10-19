package com.calairtime

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
