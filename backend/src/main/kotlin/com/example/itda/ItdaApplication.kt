package com.example.itda

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync

@SpringBootApplication
@EnableAsync
class ItdaApplication

fun main(args: Array<String>) {
    runApplication<ItdaApplication>(*args)
}
