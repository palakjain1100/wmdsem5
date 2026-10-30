package app.docqa

import com.google.cloud.Timestamp
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ExecutionException

@Service
class ConversationService(private val firestore: Firestore) {
    fun create(uid: String): ConversationSummary {
        val id = UUID.randomUUID().toString()
        val now = FieldValue.serverTimestamp()
        conversations(uid).document(id).set(
            mapOf("ownerUid" to uid, "title" to "New conversation", "createdAt" to now, "updatedAt" to now)
        ).get()
        return ConversationSummary(id, "New conversation", System.currentTimeMillis())
    }

    fun list(uid: String): List<ConversationSummary> =
        conversations(uid).get().get().documents
            .filter { it.getString("ownerUid") == uid }
            .sortedByDescending { it.getTimestamp("updatedAt")?.toDate()?.time ?: 0L }
            .map { snapshot ->
                ConversationSummary(
                    id = snapshot.id,
                    title = snapshot.getString("title") ?: "Conversation",
                    updatedAt = snapshot.getTimestamp("updatedAt")?.toDate()?.time ?: 0L
                )
            }

    fun get(uid: String, id: String): ConversationHistory {
        val conversation = conversations(uid).document(id).get().get()
        if (!conversation.exists()) throw NoSuchElementException("Conversation not found.")
        verifyOwner(uid, conversation.getString("ownerUid"))
        val messages = conversation.reference.collection("messages").get().get().documents
            .sortedBy { it.getTimestamp("createdAt")?.toDate()?.time ?: 0L }
            .map { snapshot ->
                val sources = (snapshot.get("sources") as? List<*>)?.mapNotNull { entry ->
                    val source = entry as? Map<*, *> ?: return@mapNotNull null
                    val documentId = source["documentId"] as? String ?: return@mapNotNull null
                    val filename = source["filename"] as? String ?: return@mapNotNull null
                    val page = (source["page"] as? Number)?.toInt() ?: return@mapNotNull null
                    ConversationSource(documentId, filename, page)
                } ?: emptyList()
                ConversationMessage(
                    id = snapshot.id,
                    question = snapshot.getString("question") ?: "",
                    answer = snapshot.getString("answer") ?: "",
                    timestamp = snapshot.getTimestamp("createdAt")?.toDate()?.time ?: 0L,
                    sources = sources
                )
            }
        return ConversationHistory(
            id = conversation.id,
            title = conversation.getString("title") ?: "Conversation",
            messages = messages
        )
    }

    fun saveMessage(
        uid: String,
        conversationId: String?,
        question: String,
        answer: String,
        sources: List<ConversationSource>
    ): String {
        val id = conversationId ?: UUID.randomUUID().toString()
        val reference = conversations(uid).document(id)
        val snapshot = reference.get().get()
        if (snapshot.exists()) {
            verifyOwner(uid, snapshot.getString("ownerUid"))
        } else if (conversationId != null) {
            throw NoSuchElementException("Conversation not found.")
        }
        val now = FieldValue.serverTimestamp()
        val title = if (snapshot.exists()) snapshot.getString("title") ?: question.take(100) else question.take(100)
        reference.set(
            mapOf(
                "ownerUid" to uid,
                "title" to title,
                "createdAt" to (snapshot.getTimestamp("createdAt") ?: now),
                "updatedAt" to now
            )
        ).get()
        reference.collection("messages").document(UUID.randomUUID().toString()).set(
            mapOf(
                "ownerUid" to uid,
                "question" to question,
                "answer" to answer,
                "sources" to sources.map {
                    mapOf("documentId" to it.documentId, "filename" to it.filename, "page" to it.page)
                },
                "createdAt" to now
            )
        ).get()
        return id
    }

    private fun conversations(uid: String) =
        firestore.collection("users").document(uid).collection("conversations")

    private fun verifyOwner(uid: String, ownerUid: String?) {
        if (ownerUid != uid) throw SecurityException("You do not own this conversation.")
    }
}
