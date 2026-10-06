# Internal RAG Assistant

Spring Boot + Spring Security + Thymeleaf + H2 + Spring AI + Gemini. No Docker and no Keycloak.

## Requirements
- Java 25
- Maven 3.9+
- Gemini API key
- Gemini chat model defaults to `gemini-3.5-flash-lite`. Set `GEMINI_CHAT_MODEL` to override it.
- Gemini requests time out after 30 seconds; transient AI errors are retried once.

## Run on Windows PowerShell
```powershell
$env:GEMINI_API_KEY="YOUR_GEMINI_API_KEY"
mvn clean spring-boot:run
```

Run both commands in the same PowerShell window. The key is required at startup because the app creates the Gemini chat and embedding clients before serving requests. If startup reports missing Google GenAI credentials, set the environment variable again in that terminal and retry:

```powershell
if ([string]::IsNullOrWhiteSpace($env:GEMINI_API_KEY)) {
    Write-Error "GEMINI_API_KEY is not set in this PowerShell window."
}
```

Open http://localhost:8080

## Demo accounts
- internal / User@123 — USER: RAG chat only
- admin / Admin@123 — ADMIN: RAG chat + document indexing

## Demo flow
1. Sign in as `admin`.
2. Upload `src/main/resources/data/sample.txt` or another PDF/TXT/MD document.
3. Sign out.
4. Sign in as `internal`.
5. Ask a question answered by the uploaded document and verify `[1]` citations.
6. Ask an unrelated question. The app refuses because no relevant context is retrieved.
7. As `internal`, open `/admin/documents`: access is denied (403).
8. As `admin`, open the same URL: access is granted.

The vector store is persisted at `data/vectors.json`; H2 data is persisted under `data/`.

## User experience
- Each signed-in user has a private conversation history, with answer feedback saved alongside the chat.
- Open a citation to inspect the retrieved excerpt or open its original uploaded file.
- Admins can search indexed documents, open originals, refresh an index, or remove a document and its vectors.
- The assistant distinguishes missing sources from temporary retrieval/model errors.
