package app.docqa

import jakarta.validation.constraints.NotBlank
import java.time.Instant

data class AuthenticatedUser(val uid: String, val email: String?)
data class AskRequest(
    @field:NotBlank val question: String,
    val conversationId: String? = null,
    val documentIds: List<String> = emptyList()
)
data class SourcePassage(
    val documentId: String,
    val document: String,
    val page: Int,
    val text: String,
    val score: Double
)
data class AskResponse(val answer: String, val sources: List<SourcePassage>, val conversationId: String = "")
data class DocumentInfo(
    val id: String,
    val name: String,
    val chunks: Int,
    val origin: String,
    val status: String,
    val createdAt: Instant
)
data class IngestResponse(val indexed: Int, val documents: List<DocumentInfo>)
data class Chunk(val id: String, val text: String, val documentId: String, val document: String, val page: Int)
data class DocumentRecord(
    val id: String,
    val ownerUid: String,
    val name: String,
    val storedName: String,
    val path: String,
    val origin: String,
    val status: String,
    val chunks: Int,
    val createdAt: Instant
) {
    fun toInfo() = DocumentInfo(id, name, chunks, origin, status, createdAt)
}
data class ConversationSource(val documentId: String, val filename: String, val page: Int)
data class ConversationMessage(
    val id: String,
    val question: String,
    val answer: String,
    val timestamp: Long,
    val sources: List<ConversationSource>
)
data class ConversationSummary(val id: String, val title: String, val updatedAt: Long)
data class ConversationHistory(val id: String, val title: String, val messages: List<ConversationMessage>)
