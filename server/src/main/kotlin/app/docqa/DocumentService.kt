package app.docqa

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class DocumentService(
    private val rag: RagService,
    private val mapper: ObjectMapper,
    @Value("\${app.pdf-library-path}") private val libraryPath: String,
    @Value("\${app.upload-dir}") uploadDirectory: String,
    @Value("\${app.document-metadata-dir}") metadataDirectory: String,
    @Value("\${app.chunk-size}") private val chunkSize: Int,
    @Value("\${app.chunk-overlap}") private val overlap: Int
) {
    private val uploadDir = Path.of(uploadDirectory).toAbsolutePath().normalize()
    private val metadataDir = Path.of(metadataDirectory).toAbsolutePath().normalize()
    private val records = ConcurrentHashMap<String, DocumentRecord>()

    init {
        Files.createDirectories(uploadDir)
        Files.createDirectories(metadataDir)
        Files.list(metadataDir).use { files ->
            files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .forEach { path ->
                    val record = mapper.readValue(path.toFile(), DocumentRecord::class.java)
                    records[record.id] = record
                }
        }
    }

    @Synchronized
    fun upload(uid: String, filename: String, bytes: ByteArray): DocumentInfo {
        require(filename.lowercase().endsWith(".pdf")) { "Only PDF files can be uploaded." }
        require(bytes.size <= MAX_PDF_BYTES) { "PDF files must be 50 MB or smaller." }
        require(bytes.size >= PDF_HEADER.size && bytes.copyOfRange(0, PDF_HEADER.size).contentEquals(PDF_HEADER)) {
            "The uploaded file is not a valid PDF."
        }
        val id = UUID.randomUUID().toString()
        val safeName = safeFilename(filename)
        val ownerDir = uploadDir.resolve(uid).normalize()
        require(ownerDir.startsWith(uploadDir)) { "Invalid user storage path." }
        Files.createDirectories(ownerDir)
        val destination = ownerDir.resolve("$id-$safeName").normalize()
        require(destination.startsWith(ownerDir)) { "Invalid document filename." }
        Files.write(destination, bytes)
        val record = DocumentRecord(
            id = id,
            ownerUid = uid,
            name = safeName,
            storedName = destination.fileName.toString(),
            path = destination.toString(),
            origin = "upload",
            status = "UPLOADED",
            chunks = 0,
            createdAt = Instant.now()
        )
        records[id] = record
        persist(record)
        return record.toInfo()
    }

    @Synchronized
    fun ingestLibrary(uid: String): IngestResponse {
        val root = File(libraryPath).canonicalFile
        val files = root.listFiles { file -> file.isFile && file.extension.equals("pdf", true) }
            ?.sortedBy { it.name } ?: emptyList()
        require(files.isNotEmpty()) { "No PDFs found in $libraryPath" }
        val docs = files.map { file ->
            val id = UUID.nameUUIDFromBytes("$uid:library:${file.name}".toByteArray()).toString()
            val existing = records[id]
            val record = existing ?: DocumentRecord(
                id = id,
                ownerUid = uid,
                name = safeFilename(file.name),
                storedName = file.name,
                path = file.canonicalPath,
                origin = "library",
                status = "UPLOADED",
                chunks = 0,
                createdAt = Instant.now()
            )
            if (existing == null) {
                records[id] = record
                persist(record)
            }
            index(id, uid)
        }
        return IngestResponse(docs.sumOf { it.chunks }, docs)
    }

    fun listDocuments(uid: String): List<DocumentInfo> =
        records.values.filter { it.ownerUid == uid }.sortedBy { it.createdAt }.map { it.toInfo() }

    @Synchronized
    fun index(id: String, uid: String): DocumentInfo {
        val record = ownedRecord(id, uid)
        require(record.status != "INDEXING") { "This document is already being indexed." }
        val indexing = record.copy(status = "INDEXING")
        records[id] = indexing
        persist(indexing)
        try {
            val chunks = extractChunks(indexing)
            val count = rag.upsert(chunks, uid)
            val indexed = indexing.copy(status = "INDEXED", chunks = count)
            records[id] = indexed
            persist(indexed)
            return indexed.toInfo()
        } catch (error: Exception) {
            val failed = indexing.copy(status = "FAILED", chunks = 0)
            records[id] = failed
            persist(failed)
            throw error
        }
    }

    @Synchronized
    fun delete(id: String, uid: String): Map<String, String> {
        val record = ownedRecord(id, uid)
        if (record.origin == "library") throw IllegalArgumentException("Bundled library PDFs cannot be deleted.")
        rag.deleteDocument(uid, id)
        Files.deleteIfExists(Path.of(record.path))
        records.remove(id)
        Files.deleteIfExists(metadataPath(id))
        return mapOf("deleted" to id)
    }

    fun validateSelection(uid: String, documentIds: List<String>): List<String> {
        if (documentIds.isEmpty()) {
            return records.values.filter { it.ownerUid == uid && it.status == "INDEXED" }.map { it.id }
        }
        val selected = documentIds.distinct()
        selected.forEach { id ->
            val record = ownedRecord(id, uid)
            require(record.status == "INDEXED") { "Document '${record.name}' must be indexed before it can be selected." }
        }
        return selected
    }

    private fun extractChunks(record: DocumentRecord): List<Chunk> {
        val source = Path.of(record.path).toAbsolutePath().normalize()
        if (record.origin == "upload") {
            require(source.startsWith(uploadDir)) { "The stored PDF path is outside the upload directory." }
        }
        require(Files.isRegularFile(source)) { "The PDF file is missing: ${record.name}" }
        val bytes = Files.readAllBytes(source)
        require(bytes.size <= MAX_PDF_BYTES) { "PDF files must be 50 MB or smaller." }
        require(bytes.size >= PDF_HEADER.size && bytes.copyOfRange(0, PDF_HEADER.size).contentEquals(PDF_HEADER)) {
            "The stored file is not a valid PDF: ${record.name}"
        }
        val chunks = mutableListOf<Chunk>()
        Loader.loadPDF(bytes).use { pdf ->
            val stripper = PDFTextStripper()
            for (page in 1..pdf.numberOfPages) {
                stripper.startPage = page
                stripper.endPage = page
                val text = stripper.getText(pdf).replace(Regex("\\s+"), " ").trim()
                if (text.isBlank()) continue
                split(text).forEachIndexed { index, part ->
                    val chunkId = MessageDigest.getInstance("SHA-256")
                        .digest("${record.id}:$page:$index".toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    chunks.add(Chunk(chunkId, part, record.id, record.name, page))
                }
            }
        }
        require(chunks.isNotEmpty()) {
            "No extractable text found in ${record.name}; scanned-image PDFs need OCR first."
        }
        return chunks
    }

    private fun split(text: String): List<String> {
        if (text.length <= chunkSize) return listOf(text)
        val parts = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + chunkSize, text.length)
            if (end < text.length) {
                val boundary = text.lastIndexOf(' ', end)
                if (boundary > start + chunkSize / 2) end = boundary
            }
            parts.add(text.substring(start, end).trim())
            if (end >= text.length) break
            start = maxOf(end - overlap, start + 1)
        }
        return parts
    }

    private fun ownedRecord(id: String, uid: String): DocumentRecord {
        val record = records[id] ?: throw NoSuchElementException("Document not found.")
        if (record.ownerUid != uid) throw SecurityException("You do not own this document.")
        return record
    }

    private fun persist(record: DocumentRecord) {
        val target = metadataPath(record.id)
        val temporary = metadataDir.resolve(".${record.id}.tmp")
        mapper.writeValue(temporary.toFile(), record)
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun metadataPath(id: String): Path {
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid document ID." }
        return metadataDir.resolve("$id.json")
    }

    private fun safeFilename(original: String): String {
        val base = original.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_')
        return base.take(180).ifBlank { "document.pdf" }
    }

    companion object {
        private const val MAX_PDF_BYTES = 50 * 1024 * 1024
        private val PDF_HEADER = "%PDF-".toByteArray()
    }
}
