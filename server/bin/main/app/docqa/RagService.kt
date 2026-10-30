package app.docqa

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import java.util.UUID

@Service
class RagService(
    private val http: RestTemplate,
    private val mapper: ObjectMapper,
    @Value("\${app.qdrant-url}") private val qdrant: String,
    @Value("\${app.qdrant-collection}") private val collection: String,
    @Value("\${app.embedding-url}") private val embeddingUrl: String,
    @Value("\${app.embedding-model}") private val embeddingModel: String,
    @Value("\${app.embedding-dimensions}") private val embeddingDimensions: Int,
    @Value("\${app.gemini-api-key}") private val geminiApiKey: String,
    @Value("\${app.gemini-model}") private val geminiModel: String,
    @Value("\${app.top-k}") private val topK: Int
) {
    init {
        require(embeddingDimensions > 0) { "EMBEDDING_DIMENSIONS must be positive." }
        require(geminiModel.matches(Regex("[A-Za-z0-9._-]+"))) { "GEMINI_MODEL contains unsupported characters." }
    }

    fun upsert(chunks: List<Chunk>, ownerUid: String): Int {
        if (chunks.isEmpty()) return 0
        chunks.chunked(24).forEach { batch ->
            val vectors = embed(batch.map { it.text })
            ensureCollection()
            val points = batch.mapIndexed { index, chunk ->
                mapOf(
                    "id" to UUID.nameUUIDFromBytes("$ownerUid:${chunk.documentId}:${chunk.id}".toByteArray()).toString(),
                    "vector" to vectors[index],
                    "payload" to mapOf(
                        "text" to chunk.text,
                        "document" to chunk.document,
                        "filename" to chunk.document,
                        "page" to chunk.page,
                        "documentId" to chunk.documentId,
                        "ownerUid" to ownerUid
                    )
                )
            }
            put("$qdrant/collections/$collection/points?wait=true", mapOf("points" to points))
        }
        return chunks.size
    }

    fun deleteDocument(ownerUid: String, documentId: String) {
        ensureCollection()
        post(
            "$qdrant/collections/$collection/points/delete?wait=true",
            mapOf(
                "filter" to mapOf(
                    "must" to listOf(
                        mapOf("key" to "ownerUid", "match" to mapOf("value" to ownerUid)),
                        mapOf("key" to "documentId", "match" to mapOf("value" to documentId))
                    )
                )
            )
        )
    }

    fun answer(question: String, ownerUid: String, documentIds: List<String>): AskResponse {
        require(geminiApiKey.isNotBlank()) { "GEMINI_API_KEY must be configured on the backend." }
        ensureCollection()
        val vector = embed(listOf(question)).single()
        val must = mutableListOf(
            mapOf("key" to "ownerUid", "match" to mapOf("value" to ownerUid))
        )
        if (documentIds.isNotEmpty()) {
            must.add(mapOf("key" to "documentId", "match" to mapOf("any" to documentIds)))
        }
        val result = post(
            "$qdrant/collections/$collection/points/query",
            mapOf(
                "query" to vector,
                "limit" to topK,
                "with_payload" to true,
                "filter" to mapOf("must" to must)
            )
        )
        val points = result.path("result").path("points").takeIf { it.isArray }
            ?: result.path("result").takeIf { it.isArray }
        val sources = points?.mapNotNull { point ->
            val payload = point.path("payload")
            val text = payload.path("text").asText("")
            val documentId = payload.path("documentId").asText("")
            if (text.isBlank() || documentId.isBlank()) null
            else SourcePassage(
                documentId = documentId,
                document = payload.path("filename").asText(payload.path("document").asText("Document")),
                page = payload.path("page").asInt(1),
                text = text,
                score = point.path("score").asDouble()
            )
        }?.distinctBy { "${it.documentId}:${it.page}:${it.text}" } ?: emptyList()
        if (sources.isEmpty()) {
            return AskResponse("No indexed passages were found in your selected documents.", emptyList())
        }
        val context = sources.mapIndexed { index, source ->
            "[${index + 1}] ${source.document}, page ${source.page}: ${source.text}"
        }.joinToString("\n\n")
        val prompt = """Answer the user's question using only the supplied document passages.
If the passages do not contain enough information, explicitly say that the uploaded documents do not provide sufficient information.
Do not use outside knowledge. Do not invent facts, filenames, page numbers, or citations.
Cite supported claims with the passage number in square brackets, such as [1].

DOCUMENT PASSAGES:
$context

QUESTION:
$question"""
        val response = generateWithGemini(prompt)
        val answer = response.path("candidates").path(0).path("content").path("parts")
            .mapNotNull { it.path("text").asText("").takeIf(String::isNotBlank) }
            .joinToString("\n").trim()
        if (answer.isBlank()) error("Gemini returned no answer. Check the configured model and API access.")
        val checkedAnswer = answer.replace(Regex("\\[(\\d+)]")) { match ->
            val citationIndex = match.groupValues[1].toIntOrNull()
            if (citationIndex != null && citationIndex in 1..sources.size) match.value else ""
        }
        return AskResponse(checkedAnswer, sources)
    }

    private fun embed(texts: List<String>): List<List<Float>> {
        val response = try {
            post("$embeddingUrl/embed", mapOf("model" to embeddingModel, "input" to texts))
        } catch (error: Exception) {
            throw IllegalStateException("Embedding service failed: ${error.message ?: "no response"}", error)
        }
        val embeddings = response.path("embeddings")
        require(embeddings.isArray && embeddings.size() == texts.size()) {
            "Embedding service returned ${embeddings.size()} vectors for ${texts.size()} inputs."
        }
        val vectors = embeddings.map { vector ->
            require(vector.isArray && vector.size() == embeddingDimensions) {
                "Embedding model '$embeddingModel' returned ${vector.size()} dimensions; expected $embeddingDimensions."
            }
            vector.map { it.floatValue() }
        }
        require(vectors.all { it.all(Float::isFinite) }) { "Embedding service returned a non-finite vector." }
        return vectors
    }

    private fun ensureCollection() {
        val details = try {
            val response = http.getForEntity("$qdrant/collections/$collection", String::class.java)
            mapper.readTree(response.body ?: "{}")
        } catch (error: HttpClientErrorException.NotFound) {
            put(
                "$qdrant/collections/$collection",
                mapOf("vectors" to mapOf("size" to embeddingDimensions, "distance" to "Cosine"))
            )
            return
        }
        val vectors = details.path("result").path("config").path("params").path("vectors")
        val size = vectors.path("size").asInt(-1)
        require(size == embeddingDimensions) {
            "Qdrant collection '$collection' has $size dimensions, but '$embeddingModel' produces $embeddingDimensions. " +
                "Choose a new QDRANT_COLLECTION; existing collections and their data were not changed."
        }
    }

    private fun generateWithGemini(prompt: String): JsonNode {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("x-goog-api-key", geminiApiKey)
        }
        val body = mapOf(
            "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt)))),
            "generationConfig" to mapOf("temperature" to 0.2)
        )
        val response = http.exchange(
            "https://generativelanguage.googleapis.com/v1beta/models/$geminiModel:generateContent",
            HttpMethod.POST,
            HttpEntity(body, headers),
            String::class.java
        )
        return mapper.readTree(response.body ?: "{}")
    }

    private fun post(url: String, body: Any): JsonNode {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val response = http.exchange(url, HttpMethod.POST, HttpEntity(body, headers), String::class.java)
        return mapper.readTree(response.body ?: "{}")
    }

    private fun put(url: String, body: Any) {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        http.exchange(url, HttpMethod.PUT, HttpEntity(body, headers), String::class.java)
    }
}
