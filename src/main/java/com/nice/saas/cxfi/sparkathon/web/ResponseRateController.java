package com.nice.saas.cxfi.sparkathon.web;

import com.nice.saas.cxfi.sparkathon.csat.ResponseRateService;
import com.nice.saas.cxfi.sparkathon.model.ResponseRateResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes a tenant's survey response rate and average actual CSAT.
 * Routes under {@code /sparkathon/*}, so it is reachable through the CloudFront
 * behavior that forwards that path to the EC2 backend.
 */
@RestController
@RequestMapping("/sparkathon/csat")
@CrossOrigin
public class ResponseRateController {

    private final ResponseRateService service;

    public ResponseRateController(ResponseRateService service) {
        this.service = service;
    }

    @GetMapping("/response-rate")
    public ResponseEntity<ResponseRateResponse> responseRate(@RequestParam String tenantId) {
        return ResponseEntity.ok(service.compute(tenantId));
    }
}
