package com.nice.saas.cxfi.sparkathon.model;

import lombok.Data;

@Data
public class CsatScoreRequest {

    private String tenantId = "11f16fc2-78c4-d4a0-b24e-0242ac110002";
    private String uuid;
    private Double score;
}
