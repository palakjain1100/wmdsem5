# Document Q&A

React + Vite and Kotlin + Spring Boot document question-answering app. PDFs are stored on the local filesystem, embedded by a separate Sentence Transformers service, indexed in the existing Qdrant instance, and answered using Gemini. Firebase Google Sign-In protects the API; Firebase Admin verifies ID tokens and Firestore stores each user's conversations.

## Requirements

- Docker Desktop with Compose
- Firebase project with Google Sign-In enabled, Firestore initialized, and a Firebase Admin service-account credential
- Gemini API key configured only in the backend environment
- Existing Qdrant data is preserved. The app uses `document_chunks` (384 dimensions), which matches `sentence-transformers/all-MiniLM-L6-v2`.

## Configure and run

1. Copy `.env.example` to `.env` and set the Firebase project/web-app values, Gemini API key, and Firebase credentials path.
2. Create a Firebase service-account credential with Firebase Authentication and Firestore access. Keep it out of source control; `GOOGLE_APPLICATION_CREDENTIALS` points to the file on the host and Compose mounts it read-only into the API container.
3. Enable Google as an Authentication sign-in provider and initialize Cloud Firestore in the Firebase Console.
4. Start the application:

   ```powershell
   docker compose up --build
   ```

   The first startup downloads the configured Sentence Transformers model into the persistent `embedding_model_cache` volume. This can take several minutes.
5. Open <http://localhost:5173> and sign in with Google to add PDFs. Uploaded files and their metadata are stored locally under `./data/uploads` and `./data/documents` on the host; Compose mounts this folder into the API container so the files persist across restarts.

Compose starts the Python embedding service, Spring API, and React UI. It reuses the existing Qdrant service on the Windows host through `host.docker.internal:6333`; it does not create, replace, or delete a Qdrant container or volume. If the API is run directly on Windows, use `http://localhost:6333`. It does not start Ollama or another language-model service. Ollama installed separately on the host is not changed.

## Environment

| Variable | Purpose | Default |
|---|---|---|
| `GEMINI_API_KEY` | Backend-only Gemini API key; never pass this to Vite | unset |
| `GEMINI_MODEL` | Gemini answer-generation model | `gemini-2.5-flash` |
| `QDRANT_URL` | Qdrant REST URL for direct local API runs | `http://localhost:6333` |
| `QDRANT_URL_DOCKER` | Existing Windows-host Qdrant REST URL used by Compose | `http://host.docker.internal:6333` |
| `QDRANT_COLLECTION` | Collection for this embedding model | `document_chunks` |
| `EMBEDDING_SERVICE_URL` | Python embedding API | `http://localhost:8000` (Compose: `http://embeddings:8000`) |
| `EMBEDDING_MODEL` | Sentence Transformers model | `sentence-transformers/all-MiniLM-L6-v2` |
| `EMBEDDING_DIMENSIONS` | Expected vector size | `384` |
| `FIREBASE_PROJECT_ID` | Firebase project for token verification and Firestore | required |
| `GOOGLE_APPLICATION_CREDENTIALS` | Firebase Admin service-account credential path | `./firebase-service-account.json` |
| `VITE_FIREBASE_API_KEY` | Firebase web app API key | required |
| `VITE_FIREBASE_AUTH_DOMAIN` | Firebase web app auth domain | required |
| `VITE_FIREBASE_PROJECT_ID` | Firebase web app project ID | required |
| `VITE_FIREBASE_APP_ID` | Firebase web app ID | required |
| `UPLOAD_DIR` | Locally stored uploaded PDFs | `./data/uploads` |
| `DOCUMENT_METADATA_DIR` | Persistent per-document metadata | `./data/documents` |
| `FRONTEND_URL` | Allowed browser origin | `http://localhost:5173` |

For local development outside Compose, run the Python service from `embedding/` with the dependencies in `requirements.txt`, set its URL to `http://localhost:8000`, and run the web app from `web/` with `npm install` and `npm run dev`. Start the Kotlin API from `server/` with Gradle and set Qdrant to `http://localhost:6333`. Provide Firebase Admin credentials using Application Default Credentials or `GOOGLE_APPLICATION_CREDENTIALS`; never commit the service-account file.

## Embeddings and Qdrant data

The embedding service exposes `POST /embed` and `GET /health`. It uses one configured Sentence Transformers model for both PDF chunks and questions; the default `all-MiniLM-L6-v2` model returns normalized 384-dimensional vectors. The backend checks the response size and the Qdrant collection's configured vector size before indexing/querying.

Before switching models, check that the configured collection has the same vector dimensions. If it does not, select a new `QDRANT_COLLECTION`; the backend creates that collection with the expected dimensions and does not delete or reconfigure existing collections. Collections with incompatible dimensions must remain untouched. Retrieval is filtered by authenticated owner UID and document ID, so older unowned points are not exposed.

Qdrant REST and gRPC ports are `6333` and `6334`. `qdrant_data` preserves its existing volume. Uploaded files and JSON metadata persist in `./data/` on the host and are mounted into the API container.

## Authentication, ownership, and history

React sends the Firebase ID token in `Authorization: Bearer <token>`. Spring Security verifies the token (including revocation) through Firebase Admin and requires Google sign-in. Every document record and Qdrant point is scoped to the verified UID; document deletion and selection are ownership-checked. Firestore stores conversations at `users/{uid}/conversations/{conversationId}` and messages beneath each conversation, including source document IDs, filenames, and page numbers.

The static question bank is in `web/src/questionBank.js`. It contains structured, predefined questions and answers for Experiments 1–7; select an experiment on the home page and click a question to display its built-in answer. These replies are labelled as predefined answers and never enter the retrieval/Gemini pipeline or appear as document citations. Other questions always use the embedding → Qdrant → Gemini path; Gemini is used for answer generation only, not embeddings. Uploaded PDFs and their metadata persist in the mounted `./data/` host directory across container restarts.

## API

- `GET /api/health` — public health endpoint.
- `GET /api/auth/me` — authenticated Firebase identity.
- `GET /api/documents`, `POST /api/documents/upload` — list/upload owned PDFs.
- `POST /api/documents/{id}/index`, `DELETE /api/documents/{id}` — index/delete an owned PDF.
- `POST /api/ingest/library` — index bundled PDFs for the signed-in user.
- `POST /api/ask` — retrieve passages, generate a Gemini answer, and persist the exchange to Firestore.
- `POST /api/conversations`, `GET /api/conversations`, `GET /api/conversations/{id}` — create, list, and reopen owned chats.
