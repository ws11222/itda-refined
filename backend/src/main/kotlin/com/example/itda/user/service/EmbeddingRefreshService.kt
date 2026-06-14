package com.example.itda.user.service

import com.example.itda.embedding.service.EmbeddingService
import com.example.itda.feedCache.persistence.FeedCacheRepository
import com.example.itda.user.persistence.UserEntity
import com.example.itda.user.persistence.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.time.Period

@Service
class EmbeddingRefreshService(
    private val userRepository: UserRepository,
    private val embeddingService: EmbeddingService,
    private val feedCacheRepository: FeedCacheRepository,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(EmbeddingRefreshService::class.java)
    private val transactionTemplate = TransactionTemplate(transactionManager)

    @Async
    fun refreshUserEmbedding(userId: String) {
        val userText =
            transactionTemplate.execute { _ ->
                userRepository.findByIdOrNull(userId)?.let { generateUserText(it) }
            }

        if (userText == null) {
            log.warn("User {} not found while refreshing embedding", userId)
            return
        }

        val embedding =
            try {
                embeddingService.getEmbeddingOrThrow(userText)
            } catch (e: Exception) {
                log.error("Failed to refresh embedding for user {}: {}", userId, e.message, e)
                return
            }

        transactionTemplate.executeWithoutResult {
            val userEntity = userRepository.findByIdOrNull(userId)
            if (userEntity == null) {
                log.warn("User {} disappeared before embedding could be persisted", userId)
                return@executeWithoutResult
            }
            userEntity.embedding = embedding
            userRepository.save(userEntity)
            feedCacheRepository.deleteByUserId(userId)
        }
    }

    fun generateUserText(userEntity: UserEntity): String {
        val sb = StringBuilder()

        val age =
            userEntity.birthDate?.let {
                Period.between(it, LocalDate.now()).years
            } ?: 0

        val genderStr = userEntity.gender?.value ?: "사람"

        sb.append("저는 ${age}세 ${genderStr}입니다. ")

        userEntity.address?.let {
            sb.append("저는 ${it}에 거주하고 있습니다. ")
        }

        userEntity.employmentStatus?.let {
            sb.append("저의 고용 상태는 ${it.value}입니다. ")
        }

        userEntity.maritalStatus?.let {
            sb.append("저의 결혼 상태는 ${it.value}입니다. ")
        }

        userEntity.householdSize?.let {
            sb.append("저는 ${it}인 가구입니다. ")
        }

        userEntity.householdIncome?.let {
            sb.append("가구 소득은 연 ${it}만원입니다. ")
        }

        if (userEntity.tags.isNotEmpty()) {
            val interests = userEntity.tags.joinToString(", ") { it.name }
            sb.append("저는 $interests 분야에 관심이 있습니다. ")
        }

        sb.append("저는 제 상황에 맞는 정부 지원금과 복지 혜택, 공공 지원 프로그램을 찾고 있습니다.")

        return sb.toString().trim()
    }
}
