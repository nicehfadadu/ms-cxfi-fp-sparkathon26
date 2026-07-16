package com.nice.saas.cxfi.sparkathon.web;

import com.nice.saas.cxfi.sparkathon.client.BedrockCsatPredictor;
import com.nice.saas.cxfi.sparkathon.client.BedrockTranscriptGenerator;
import com.nice.saas.cxfi.sparkathon.client.CsatScoreRepository;
import com.nice.saas.cxfi.sparkathon.client.TopicAiPipelineService;
import com.nice.saas.cxfi.sparkathon.client.TranscriptFragmentBuilder;
import com.nice.saas.cxfi.sparkathon.client.TranscriptS3Writer;
import com.nice.saas.cxfi.sparkathon.model.CsatPrediction;
import com.nice.saas.cxfi.sparkathon.model.GenerateTranscriptRequest;
import com.nice.saas.cxfi.sparkathon.model.GenerateTranscriptResponse;
import com.nice.saas.cxfi.sparkathon.model.SendTranscriptRequest;
import com.nice.saas.cxfi.sparkathon.model.Temperament;
import com.nice.saas.cxfi.sparkathon.model.TopicType;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(TranscriptGeneratorController.class);

    private static final String TRANSCRIPT_FILE = "transcript.json";
    private static final String FRAGMENT_FILE = "transcript-fragment.json";

    private final BedrockTranscriptGenerator generator;
    private final TranscriptFragmentBuilder fragmentBuilder;
    private final TranscriptS3Writer s3Writer;
    private final CsatScoreRepository csatScoreRepository;
    private final TopicAiPipelineService pipelineService;
    private final BedrockCsatPredictor csatPredictor;

    public TranscriptGeneratorController(BedrockTranscriptGenerator generator,
                                         TranscriptFragmentBuilder fragmentBuilder,
                                         TranscriptS3Writer s3Writer,
                                         CsatScoreRepository csatScoreRepository,
                                         TopicAiPipelineService pipelineService,
                                         BedrockCsatPredictor csatPredictor) {
        this.generator = generator;
        this.fragmentBuilder = fragmentBuilder;
        this.s3Writer = s3Writer;
        this.csatScoreRepository = csatScoreRepository;
        this.pipelineService = pipelineService;
        this.csatPredictor = csatPredictor;
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
        // populated later by the TopicAI pipeline, so they are null at creation time.
        csatScoreRepository.save(fragment.getTenantId(), transcriptId, s3Uri, fragmentS3Uri,
                null, null, null);

        // Kick off the TopicAI pipeline in the background: reads the fragment from S3,
        // sends it to the eligibility MS, polls for the result, and updates DynamoDB
        // with the detected topics and subtopics.
        pipelineService.processAsync(transcriptId, fragment.getTenantId(), fragmentS3Uri);

        // The chat is now considered resolved/closed: predict CSAT inline and update the row.
        // topics_detected is owned by the TopicAI pipeline (above), so pass null here to
        // avoid racing with it — we only write the prediction fields.
        CsatPrediction prediction = null;
        try {
            prediction = csatPredictor.predict(transcript, topicPhrase, null);
            csatScoreRepository.updatePrediction(
                    fragment.getTenantId(),
                    transcriptId,
                    prediction.getPredictedCsat() == null ? null : prediction.getPredictedCsat().doubleValue(),
                    prediction.getConfidence(),
                    null);
        } catch (RuntimeException e) {
            log.error("CSAT prediction failed for transcriptId={}; row saved without prediction", transcriptId, e);
        }

        GenerateTranscriptResponse response = new GenerateTranscriptResponse();
        response.setTranscriptId(transcriptId);
        response.setS3Uri(s3Uri);
        response.setFragmentS3Uri(fragmentS3Uri);
        response.setTranscript(transcript);
        response.setPrediction(prediction);
        return ResponseEntity.ok(response);
    }
}
