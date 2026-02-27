package com.blogwriter

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class BlogWriterApplication

fun main(args: Array<String>) {
    runApplication<BlogWriterApplication>(*args)
}
