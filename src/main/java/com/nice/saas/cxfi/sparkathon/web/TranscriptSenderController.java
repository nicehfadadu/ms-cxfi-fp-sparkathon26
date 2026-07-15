package com.nice.saas.cxfi.sparkathon.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nice.saas.cxfi.sparkathon.client.TopicAiClient;
import com.nice.saas.cxfi.sparkathon.model.SendTranscriptRequest;
import com.nice.saas.cxfi.sparkathon.model.SendTranscriptResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;

/**
 * Local API to trigger sending a transcript to the Feedback Intelligence {@code /topic-ai} service.
 */
@RestController
@RequestMapping("/sparkathon/topic-ai")
public class TranscriptSenderController {

    private final TopicAiClient topicAiClient;
    private final ObjectMapper objectMapper;

    public TranscriptSenderController(TopicAiClient topicAiClient, ObjectMapper objectMapper) {
        this.topicAiClient = topicAiClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Forwards a caller-supplied transcript to the {@code /topic-ai} API.
     */
    @PostMapping("/send")
    public ResponseEntity<SendTranscriptResponse> send(@RequestBody SendTranscriptRequest request) {
        return ResponseEntity.ok(topicAiClient.sendTranscript(request));
    }

    /**
     * Sends the bundled sample transcript to the {@code /topic-ai} API — handy for quick testing.
     */
    @PostMapping("/send-sample")
    public ResponseEntity<SendTranscriptResponse> sendSample() throws IOException {
        try (InputStream in = new ClassPathResource("sample-transcript.json").getInputStream()) {
            SendTranscriptRequest request = objectMapper.readValue(in, SendTranscriptRequest.class);
            return ResponseEntity.ok(topicAiClient.sendTranscript(request));
        }
    }
}
