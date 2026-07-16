package com.nice.saas.cxfi.sparkathon.web;

import com.nice.saas.cxfi.sparkathon.client.BedrockTranscriptGenerator;
import com.nice.saas.cxfi.sparkathon.client.CsatScoreRepository;
import com.nice.saas.cxfi.sparkathon.client.TranscriptFragmentBuilder;
import com.nice.saas.cxfi.sparkathon.client.TranscriptS3Writer;
import com.nice.saas.cxfi.sparkathon.model.GenerateTranscriptRequest;
import com.nice.saas.cxfi.sparkathon.model.GenerateTranscriptResponse;
import com.nice.saas.cxfi.sparkathon.model.SendTranscriptRequest;
import com.nice.saas.cxfi.sparkathon.model.Temperament;
import com.nice.saas.cxfi.sparkathon.model.TopicType;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generates a sample agent/customer chat transcript via Amazon Bedrock and stores it in S3.
 */
@RestController
@RequestMapping("/sparkathon/transcript")
public class TranscriptGeneratorController {

    private static final String TRANSCRIPT_FILE = "transcript.json";
    private static final String FRAGMENT_FILE = "transcript-fragment.json";

    private final BedrockTranscriptGenerator generator;
    private final TranscriptFragmentBuilder fragmentBuilder;
    private final TranscriptS3Writer s3Writer;
    private final CsatScoreRepository csatScoreRepository;

    public TranscriptGeneratorController(BedrockTranscriptGenerator generator,
                                         TranscriptFragmentBuilder fragmentBuilder,
                                         TranscriptS3Writer s3Writer,
                                         CsatScoreRepository csatScoreRepository) {
        this.generator = generator;
        this.fragmentBuilder = fragmentBuilder;
        this.s3Writer = s3Writer;
        this.csatScoreRepository = csatScoreRepository;
    }

    /**
     * Generates a transcript for the given topic and customer temperament, stores it in S3
     * under a freshly generated UUID, and returns the transcript with that id.
     */
    @PostMapping("/generate")
    public ResponseEntity<GenerateTranscriptResponse> generate(@Valid @RequestBody GenerateTranscriptRequest request) {
        String topicPhrase = TopicType.fromKey(request.getTopic()).getPhrase();
        String temperamentPhrase = Temperament.fromKey(request.getTemperament()).getPhrase();

        String transcriptId = UUID.randomUUID().toString();
        List<Map<String, String>> transcript = generator.generate(topicPhrase, temperamentPhrase);
        String s3Uri = s3Writer.save(transcriptId, TRANSCRIPT_FILE, transcript);

        SendTranscriptRequest fragment = fragmentBuilder.build(transcriptId, transcript);
        String fragmentS3Uri = s3Writer.save(transcriptId, FRAGMENT_FILE, fragment);

        // Record the transcript in DynamoDB. CSAT scores and detected topics are
        // populated later by the scoring step, so they are null at creation time.
        csatScoreRepository.save(fragment.getTenantId(), transcriptId, s3Uri, fragmentS3Uri,
                null, null, null);

        GenerateTranscriptResponse response = new GenerateTranscriptResponse();
        response.setTranscriptId(transcriptId);
        response.setS3Uri(s3Uri);
        response.setFragmentS3Uri(fragmentS3Uri);
        response.setTranscript(transcript);
        return ResponseEntity.ok(response);
    }
}
