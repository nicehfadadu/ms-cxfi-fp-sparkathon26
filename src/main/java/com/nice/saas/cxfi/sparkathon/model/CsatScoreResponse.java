package com.nice.saas.cxfi.sparkathon.model;

import lombok.Data;

@Data
public class CsatScoreResponse {

    private String tenantId;
    private String uuid;
    private Double actualCsatScore;
}
