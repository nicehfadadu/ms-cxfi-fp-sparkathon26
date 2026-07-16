package com.nice.saas.cxfi.sparkathon.web;

import com.nice.saas.cxfi.sparkathon.model.ScaleQuestionRequest;
import com.nice.saas.cxfi.sparkathon.service.ScaleQuestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/sparkathon/scale-questions")
public class ScaleQuestionController {

    private final ScaleQuestionService scaleQuestionService;

    public ScaleQuestionController(ScaleQuestionService scaleQuestionService) {
        this.scaleQuestionService = scaleQuestionService;
    }

    @PostMapping
    public ResponseEntity<Map<String, List<String>>> generate(@RequestBody ScaleQuestionRequest request)
            throws IOException {
        Map<String, List<String>> result = scaleQuestionService.generateScaleQuestions(
                request.getTenantId(),
                request.getUuid()
        );
        return ResponseEntity.ok(result);
    }
}
