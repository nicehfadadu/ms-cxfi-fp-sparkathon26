# ms-cxfi-fp-sparkathon26

A small standalone Spring Boot client that calls the Feedback Intelligence **send transcript / `/topic-ai`** API exposed by `ms-cxfi-eligibility-engine`.

## What it does

Sends an interaction transcript to:

```
POST {topic-ai.base-url}/feedback-intelligence/transcripts/topic-ai
```

The payload matches `SendTranscriptRequest` (tenantId, language, correlationId, modelId, modelVersion, phrases[]). The target service returns `202 Accepted` with the accepted `correlationId`.

## Configuration

Configured in [application.yml](src/main/resources/application.yml) or via environment variables:

| Property | Env var | Default |
| --- | --- | --- |
| `topic-ai.base-url` | `TOPIC_AI_BASE_URL` | `http://localhost:8080` |
| `topic-ai.send-transcript-path` | – | `/feedback-intelligence/transcripts/topic-ai` |
| `topic-ai.bearer-token` | `TOPIC_AI_BEARER_TOKEN` | _(empty)_ |

## Run

```bash
mvn spring-boot:run
```

The app starts on port `8090`.

## Endpoints

Send the bundled sample transcript:

```bash
curl -X POST http://localhost:8090/sparkathon/topic-ai/send-sample
```

Send your own transcript:

```bash
curl -X POST http://localhost:8090/sparkathon/topic-ai/send \
  -H "Content-Type: application/json" \
  -d @src/main/resources/sample-transcript.json
```

Both return the downstream response, e.g.:

```json
{ "body": { "correlationId": "a4e9b1c2-..." }, "statusCode": 202 }
```
