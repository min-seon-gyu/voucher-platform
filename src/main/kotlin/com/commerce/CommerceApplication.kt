package com.commerce

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class CommerceApplication

fun main(args: Array<String>) {
    runApplication<CommerceApplication>(*args)
}
