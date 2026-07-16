package com.nice.saas.cxfi.sparkathon.web;

import com.nice.saas.cxfi.sparkathon.client.TopicAiPipelineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Triggers the end-to-end TopicAI pipeline for a stored transcript:
 * reads the fragment from S3, sends it to the eligibility MS, polls for the result,
 * then writes the detected topics and subtopics back to DynamoDB.
 */
@RestController
@RequestMapping("/sparkathon/topic-ai")
public class TopicAiPipelineController {

    private final TopicAiPipelineService pipelineService;

    public TopicAiPipelineController(TopicAiPipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    /**
     * Runs the TopicAI pipeline for a single transcript that is already stored in S3.
     *
     * @param request pipeline request containing transcriptId, tenantId and the S3 fragment URI
     */
    @PostMapping("/process")
    public ResponseEntity<TopicAiPipelineService.PipelineResult> process(
            @RequestBody ProcessRequest request) {
        TopicAiPipelineService.PipelineResult result = pipelineService.process(
                request.getTranscriptId(),
                request.getTenantId(),
                request.getFragmentS3Uri());
        return result.isSuccess()
                ? ResponseEntity.ok(result)
                : ResponseEntity.internalServerError().body(result);
    }

    public static class ProcessRequest {
        private String transcriptId;
        private String tenantId;
        private String fragmentS3Uri;

        public String getTranscriptId() { return transcriptId; }
        public void setTranscriptId(String transcriptId) { this.transcriptId = transcriptId; }

        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }

        public String getFragmentS3Uri() { return fragmentS3Uri; }
        public void setFragmentS3Uri(String fragmentS3Uri) { this.fragmentS3Uri = fragmentS3Uri; }
    }
}
