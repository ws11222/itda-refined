package com.example.itda.feedCache.service

import com.example.itda.feedCache.persistence.FeedCacheEntity
import com.example.itda.feedCache.persistence.FeedCacheItem
import com.example.itda.feedCache.persistence.FeedCacheRepository
import com.example.itda.program.config.AppConstants
import com.example.itda.program.persistence.BookmarkRepository
import com.example.itda.program.persistence.ProgramLikeRepository
import com.example.itda.program.persistence.ProgramRepository
import com.example.itda.program.persistence.enums.ProgramCategory
import com.example.itda.user.UserNotFoundException
import com.example.itda.user.persistence.TagRepository
import com.example.itda.user.persistence.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import kotlin.math.roundToInt
import kotlin.text.get

private const val EMBEDDING_DIMENSION = AppConstants.EMBEDDING_DIMENSION
private const val RECENT_PROGRAM_LIMIT = AppConstants.CACHE_RECENT_PROGRAM_LIMIT
private const val W_U = AppConstants.CACHE_W_U
private const val W_L = AppConstants.CACHE_W_L
private const val W_B = AppConstants.CACHE_W_B
private const val W_S = AppConstants.CACHE_W_S
private const val CACHE_CORRECTION = AppConstants.CACHE_CORRECTION

private const val ALL = "ALL"

@Service
class FeedCacheService(
    private val feedCacheRepository: FeedCacheRepository,
    private val userRepository: UserRepository,
    private val programRepository: ProgramRepository,
    private val likeRepository: ProgramLikeRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val tagRepository: TagRepository,
) {
    @Transactional
    fun getUserFeedCache(
        userId: String,
        category: ProgramCategory?,
    ): List<FeedCacheItem> {
        val key = category?.name ?: ALL
        val cache = feedCacheRepository.findByUserId(userId)

        return cache
            ?.takeUnless { it.isExpired }
            ?.categoryFeeds?.get(key)
            ?: generateFeedCache(userId)[key]
            ?: emptyList()
    }

    @Transactional
    fun generateFeedCache(userId: String): Map<String, List<FeedCacheItem>> {
        val userEntity = userRepository.findByIdOrNull(userId) ?: throw UserNotFoundException()

        val keywords = listOf("장애", "탈북", "북한")

        val userEmbedding = userEntity.embedding ?: FloatArray(EMBEDDING_DIMENSION) { 0f }
        val likesEmbedding = getLikesEmbedding(userId)
        val bookmarksEmbedding = getBookmarksEmbedding(userId)
        val seeLessEmbedding = getSeeLessEmbedding(userId)

        val preferenceEmbedding =
            getPreferenceEmbedding(userEmbedding, likesEmbedding, bookmarksEmbedding, seeLessEmbedding)
        val preferenceWithoutSeeLessEmbedding =
            getPreferenceWithoutSeeLessEmbedding(userEmbedding, likesEmbedding, bookmarksEmbedding)

        val scoreRows =
            programRepository.findFeedScoresByUserInfo(
                userId = userId,
                pref = preferenceEmbedding.toPgVectorLiteral(),
                prefNoSeeless = preferenceWithoutSeeLessEmbedding.toPgVectorLiteral(),
                likes = likesEmbedding.toPgVectorLiteral(),
                bookmarks = bookmarksEmbedding.toPgVectorLiteral(),
            )

        val categoryFeeds = getEmptyCategoryFeeds()

        val feedPairs =
            scoreRows.map { row ->
                val id = (row[0] as Number).toLong()
                val category = ProgramCategory.valueOf(row[1] as String)
                val title = row[2] as String
                var score = (row[3] as Number).toFloat()
                val denom = (row[4] as Number).toFloat()
                val likeDot = (row[5] as Number).toFloat()
                val bookmarkDot = (row[6] as Number).toFloat()

                val multiplier = if (denom != 0f) (100f / denom) else 0f
                val likeContribution = (W_L * likeDot * multiplier).roundToInt()
                val bookmarkContribution = (W_B * bookmarkDot * multiplier).roundToInt()

                val targets = keywords.filter { keyword -> keyword in title }
                val userMatchesAllTargets =
                    targets.all { target ->
                        userEntity.tags.any { tag -> target in tag.name }
                    }
                if (!userMatchesAllTargets) {
                    score -= CACHE_CORRECTION
                }

                Pair(FeedCacheItem(id, score, likeContribution, bookmarkContribution), category)
            }.sortedByDescending { it.first.score }

        feedPairs.forEach { pair ->
            val feedCacheItem = pair.first
            val category = pair.second

            categoryFeeds[ALL]?.add(feedCacheItem)
            categoryFeeds[category.name]?.add(feedCacheItem)
        }

        val feedCache = feedCacheRepository.findByUserId(userId) ?: FeedCacheEntity(user = userEntity)
        feedCache.categoryFeeds = categoryFeeds.mapValues { it.value.toList() }.toMap()
        feedCache.updatedAt = OffsetDateTime.now()

        feedCacheRepository.save(feedCache)

        return feedCache.categoryFeeds
    }

    private fun FloatArray.toPgVectorLiteral(): String =
        joinToString(separator = ",", prefix = "[", postfix = "]")

    @Transactional(readOnly = true)
    fun getLikesEmbedding(userId: String): FloatArray {
        val embeddings =
            likeRepository.findRecentEmbeddingsByUserIdAndLikeStatus(
                userId,
                true,
                PageRequest.of(0, RECENT_PROGRAM_LIMIT),
            )

        if (embeddings.isEmpty()) {
            return FloatArray(EMBEDDING_DIMENSION) { 0f }
        }

        return calculateAverageVector(embeddings)
    }

    @Transactional(readOnly = true)
    fun getBookmarksEmbedding(userId: String): FloatArray {
        val embeddings =
            bookmarkRepository.findRecentEmbeddingsByUserId(
                userId,
                PageRequest.of(0, RECENT_PROGRAM_LIMIT),
            )

        if (embeddings.isEmpty()) {
            return FloatArray(EMBEDDING_DIMENSION) { 0f }
        }

        return calculateAverageVector(embeddings)
    }

    @Transactional(readOnly = true)
    fun getSeeLessEmbedding(userId: String): FloatArray {
        val embeddings =
            likeRepository.findRecentEmbeddingsByUserIdAndLikeStatus(
                userId,
                false,
                PageRequest.of(0, RECENT_PROGRAM_LIMIT),
            )

        if (embeddings.isEmpty()) {
            return FloatArray(EMBEDDING_DIMENSION) { 0f }
        }

        return calculateAverageVector(embeddings)
    }

    fun getPreferenceEmbedding(
        userEmbedding: FloatArray,
        likesEmbedding: FloatArray,
        bookmarksEmbedding: FloatArray,
        seeLessEmbedding: FloatArray,
    ): FloatArray {
        val embedding = FloatArray(EMBEDDING_DIMENSION) { 0f }

        for (i in 0 until EMBEDDING_DIMENSION) {
            embedding[i] += (userEmbedding[i] * W_U)
            embedding[i] += (likesEmbedding[i] * W_L)
            embedding[i] += (bookmarksEmbedding[i] * W_B)
            embedding[i] -= (seeLessEmbedding[i] * W_S)
        }

        return embedding
    }

    fun getPreferenceWithoutSeeLessEmbedding(
        userEmbedding: FloatArray,
        likesEmbedding: FloatArray,
        bookmarksEmbedding: FloatArray,
    ): FloatArray {
        val embedding = FloatArray(EMBEDDING_DIMENSION) { 0f }

        for (i in 0 until EMBEDDING_DIMENSION) {
            embedding[i] += (userEmbedding[i] * W_U)
            embedding[i] += (likesEmbedding[i] * W_L)
            embedding[i] += (bookmarksEmbedding[i] * W_B)
        }

        return embedding
    }

    fun getEmptyCategoryFeeds(): MutableMap<String, MutableList<FeedCacheItem>> {
        val categoryFeeds = mutableMapOf<String, MutableList<FeedCacheItem>>()

        categoryFeeds[ALL] = mutableListOf()

        ProgramCategory.entries.forEach { category ->
            categoryFeeds[category.name] = mutableListOf()
        }

        return categoryFeeds
    }

    fun calculateAverageVector(vectors: List<FloatArray>): FloatArray {
        val result = FloatArray(EMBEDDING_DIMENSION) { 0f }

        for (vector in vectors) {
            for (i in 0 until EMBEDDING_DIMENSION) {
                result[i] += vector[i]
            }
        }

        val count = vectors.size.toFloat()
        for (i in 0 until EMBEDDING_DIMENSION) {
            result[i] /= count
        }

        return result
    }
}
