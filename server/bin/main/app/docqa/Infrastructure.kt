package app.docqa

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.firestore.Firestore
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.cloud.FirestoreClient
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClientException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.bind.annotation.*
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.client.RestTemplate
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class AppConfig(
    @Value("\${app.firebase-project-id}") private val firebaseProjectId: String,
    @Value("\${app.frontend-url}") private val frontendUrl: String
) : WebMvcConfigurer {
    @Bean
    fun restTemplate() = RestTemplate()

    @Bean
    fun firebaseAuth(): FirebaseAuth {
        require(firebaseProjectId.isNotBlank()) { "FIREBASE_PROJECT_ID must be configured" }
        if (FirebaseApp.getApps().isEmpty()) {
            FirebaseApp.initializeApp(
                FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.getApplicationDefault())
                    .setProjectId(firebaseProjectId)
                    .build()
            )
        }
        return FirebaseAuth.getInstance()
    }

    @Bean
    fun firestore(): Firestore = FirestoreClient.getFirestore(firebaseAuth().app)

    @Bean
    fun firebaseSecurityFilterChain(http: HttpSecurity, firebaseAuth: FirebaseAuth, mapper: ObjectMapper): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors(Customizer.withDefaults())
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/api/health").permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    response.status = HttpServletResponse.SC_UNAUTHORIZED
                    response.contentType = MediaType.APPLICATION_JSON_VALUE
                    response.writer.write(mapper.writeValueAsString(mapOf("error" to "A valid Firebase ID token is required")))
                }
                it.accessDeniedHandler { _, response, _ ->
                    response.status = HttpServletResponse.SC_FORBIDDEN
                    response.contentType = MediaType.APPLICATION_JSON_VALUE
                    response.writer.write(mapper.writeValueAsString(mapOf("error" to "Access denied")))
                }
            }
            .addFilterBefore(FirebaseAuthenticationFilter(firebaseAuth), UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**").allowedOrigins(frontendUrl)
            .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
            .allowedHeaders("Authorization", "Content-Type")
    }
}

class FirebaseAuthenticationFilter(private val firebaseAuth: FirebaseAuth) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authorization = request.getHeader("Authorization")
        val idToken = authorization?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
        if (!idToken.isNullOrBlank()) {
            try {
                val decodedToken = firebaseAuth.verifyIdToken(idToken, true)
                val signInProvider = (decodedToken.claims["firebase"] as? Map<*, *>)?.get("sign_in_provider")
                if (signInProvider == "google.com") {
                    val principal = AuthenticatedUser(decodedToken.uid, decodedToken.email)
                    SecurityContextHolder.getContext().authentication =
                        UsernamePasswordAuthenticationToken(principal, null, emptyList())
                }
            } catch (_: Exception) {
                SecurityContextHolder.clearContext()
            }
        }
        filterChain.doFilter(request, response)
    }
}

@RestController
@RequestMapping("/api")
class ApiController(
    private val ragService: RagService,
    private val documentService: DocumentService,
    private val conversationService: ConversationService
) {
    @GetMapping("/health")
    fun health() = mapOf("status" to "ok")

    @GetMapping("/auth/me")
    fun currentUser(@AuthenticationPrincipal user: AuthenticatedUser) = user

    @PostMapping("/ingest/library")
    fun ingestLibrary(@AuthenticationPrincipal user: AuthenticatedUser) = documentService.ingestLibrary(user.uid)

    @PostMapping("/documents/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@RequestParam("file") file: org.springframework.web.multipart.MultipartFile, @AuthenticationPrincipal user: AuthenticatedUser) =
        documentService.upload(user.uid, file.originalFilename ?: "document.pdf", file.bytes)

    @GetMapping("/documents")
    fun documents(@AuthenticationPrincipal user: AuthenticatedUser) = documentService.listDocuments(user.uid)

    @PostMapping("/documents/{id}/index")
    fun indexDocument(@PathVariable id: String, @AuthenticationPrincipal user: AuthenticatedUser) =
        documentService.index(id, user.uid)

    @DeleteMapping("/documents/{id}")
    fun deleteDocument(@PathVariable id: String, @AuthenticationPrincipal user: AuthenticatedUser) =
        documentService.delete(id, user.uid)

    @PostMapping("/conversations")
    fun newConversation(@AuthenticationPrincipal user: AuthenticatedUser) = conversationService.create(user.uid)

    @GetMapping("/conversations")
    fun conversations(@AuthenticationPrincipal user: AuthenticatedUser) = conversationService.list(user.uid)

    @GetMapping("/conversations/{id}")
    fun conversation(@PathVariable id: String, @AuthenticationPrincipal user: AuthenticatedUser) =
        conversationService.get(user.uid, id)

    @PostMapping("/ask")
    fun ask(
        @RequestBody @jakarta.validation.Valid request: AskRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): AskResponse {
        val documentIds = documentService.validateSelection(user.uid, request.documentIds)
        val response = ragService.answer(request.question, user.uid, documentIds)
        val conversationId = conversationService.saveMessage(
            user.uid,
            request.conversationId,
            request.question,
            response.answer,
            response.sources.map { ConversationSource(it.documentId, it.document, it.page) }
        )
        return response.copy(conversationId = conversationId)
    }

    @RestControllerAdvice
    class ApiExceptionHandler {
        @ExceptionHandler(IllegalArgumentException::class)
        fun badRequest(error: IllegalArgumentException) =
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to (error.message ?: "Invalid request.")))

        @ExceptionHandler(SecurityException::class)
        fun forbidden(error: SecurityException) =
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to (error.message ?: "Access denied.")))

        @ExceptionHandler(NoSuchElementException::class)
        fun notFound(error: NoSuchElementException) =
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to (error.message ?: "Not found.")))

        @ExceptionHandler(RestClientException::class)
        fun dependencyFailure(error: RestClientException) =
            ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(mapOf("error" to "A required AI or vector service request failed."))

        @ExceptionHandler(IllegalStateException::class)
        fun serviceFailure(error: IllegalStateException) =
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("error" to (error.message ?: "A required service is unavailable.")))
    }
}
