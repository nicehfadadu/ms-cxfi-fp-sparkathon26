package com.nice.saas.cxfi.sparkathon.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nice.saas.cxfi.sparkathon.client.CsatScoreRepository;
import com.nice.saas.cxfi.sparkathon.insights.TranscriptSampler;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ScaleQuestionService {

    private static final Logger log = LoggerFactory.getLogger(ScaleQuestionService.class);

    private static final String TRANSCRIPT_PLACEHOLDER = "{TRANSCRIPT}";
    private static final String TOPIC_AI_RESPONSE_PLACEHOLDER = "{TOPIC_AI_RESPONSE}";

    private final BedrockRuntimeClient bedrockRuntimeClient;
    private final ObjectMapper objectMapper;
    private final CsatScoreRepository csatScoreRepository;
    private final TranscriptSampler transcriptSampler;

    @Value("${aws.bedrock.model-id:us.amazon.nova-pro-v1:0}")
    private String modelId;

    private String promptTemplate;

    public ScaleQuestionService(BedrockRuntimeClient bedrockRuntimeClient,
                                ObjectMapper objectMapper,
                                CsatScoreRepository csatScoreRepository,
                                TranscriptSampler transcriptSampler) {
        this.bedrockRuntimeClient = bedrockRuntimeClient;
        this.objectMapper = objectMapper;
        this.csatScoreRepository = csatScoreRepository;
        this.transcriptSampler = transcriptSampler;
    }

    @PostConstruct
    void loadPromptTemplate() throws IOException {
        ClassPathResource resource = new ClassPathResource("prompts/scale-question-prompt.txt");
        promptTemplate = resource.getContentAsString(StandardCharsets.UTF_8);
        log.info("Loaded scale-question prompt template ({} chars)", promptTemplate.length());
    }

    public Map<String, List<String>> generateScaleQuestions(String tenantId, String uuid) throws IOException {
        Map<String, AttributeValue> item = csatScoreRepository.getItem(tenantId, uuid);

        String s3Path = item.get("transcript_s3_path").s();
        String transcript = transcriptSampler.excerpts(List.of(s3Path)).get(0);

        String topicAiJson = item.get("topicai_response").s();
        Map<String, Object> topicAiResponse = objectMapper.readValue(
                topicAiJson, new TypeReference<Map<String, Object>>() {});

        return generateScaleQuestions(transcript, topicAiResponse);
    }

    public Map<String, List<String>> generateScaleQuestions(String transcript,
                                                             Map<String, Object> topicAiResponse) throws IOException {
        String topicAiJson = objectMapper.writeValueAsString(topicAiResponse);

        String prompt = promptTemplate
                .replace(TRANSCRIPT_PLACEHOLDER, transcript)
                .replace(TOPIC_AI_RESPONSE_PLACEHOLDER, topicAiJson);

        log.info("Invoking Bedrock model={} for scale question generation", modelId);

        ConverseRequest request = ConverseRequest.builder()
                .modelId(modelId)
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(prompt))
                        .build())
                .build();

        ConverseResponse response = bedrockRuntimeClient.converse(request);

        String rawText = response.output().message().content().stream()
                .filter(cb -> cb.text() != null)
                .map(ContentBlock::text)
                .collect(Collectors.joining("\n"))
                .trim();

        log.info("Bedrock response received ({} chars)", rawText.length());

        // Extract the JSON object the prompt instructs the model to return: {"question": "..."}
        Map<?, ?> parsed = objectMapper.readValue(rawText, Map.class);
        String question = (String) parsed.get("question");

        return Map.of("questions", List.of(question));
    }
}
