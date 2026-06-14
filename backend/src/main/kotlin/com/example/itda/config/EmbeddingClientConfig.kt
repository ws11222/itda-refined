package com.example.itda.config

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.util.concurrent.TimeUnit

private const val CONNECT_TIMEOUT_MS = 5_000
private const val RESPONSE_TIMEOUT_SECONDS = 10L
private const val READ_WRITE_TIMEOUT_SECONDS = 10L

@Configuration
@Profile("!test")
class EmbeddingClientConfig() {
    @Bean
    fun embeddingWebClient(builder: WebClient.Builder): WebClient {
        val embeddingServerUrl =
            System.getProperty("EMBEDDING_SERVER_URL")
                ?: System.getenv("EMBEDDING_SERVER_URL")
                ?: throw IllegalStateException("EMBEDDING_SERVER_URL is not set!")

        val embeddingServerApiKey =
            System.getProperty("EMBEDDING_SERVER_API_KEY")
                ?: System.getenv("EMBEDDING_SERVER_API_KEY")
                ?: throw IllegalStateException("EMBEDDING_SERVER_API_KEY is not set!")

        val httpClient =
            HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(Duration.ofSeconds(RESPONSE_TIMEOUT_SECONDS))
                .doOnConnected { conn ->
                    conn.addHandlerLast(ReadTimeoutHandler(READ_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    conn.addHandlerLast(WriteTimeoutHandler(READ_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                }

        return builder
            .baseUrl(embeddingServerUrl)
            .defaultHeader("X-API-Key", embeddingServerApiKey)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
