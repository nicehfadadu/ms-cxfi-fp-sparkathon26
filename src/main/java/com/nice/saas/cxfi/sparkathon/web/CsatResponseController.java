package com.nice.saas.cxfi.sparkathon.web;

import com.nice.saas.cxfi.sparkathon.client.CsatScoreRepository;
import com.nice.saas.cxfi.sparkathon.model.CsatScoreRequest;
import com.nice.saas.cxfi.sparkathon.model.CsatScoreResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sparkathon/response")
public class CsatResponseController {

    private final CsatScoreRepository csatScoreRepository;

    public CsatResponseController(CsatScoreRepository csatScoreRepository) {
        this.csatScoreRepository = csatScoreRepository;
    }

    @PostMapping
    public ResponseEntity<CsatScoreResponse> saveResponse(@RequestBody CsatScoreRequest request) {
        csatScoreRepository.getItem(request.getTenantId(), request.getUuid());
        csatScoreRepository.updateActualCsatScore(request.getTenantId(), request.getUuid(), request.getScore());

        CsatScoreResponse response = new CsatScoreResponse();
        response.setTenantId(request.getTenantId());
        response.setUuid(request.getUuid());
        response.setActualCsatScore(request.getScore());

        return ResponseEntity.ok(response);
    }
}
