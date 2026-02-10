package io.camunda.bizsol.bb.comm_agent.services;

import io.camunda.bizsol.bb.comm_agent.models.ApiResponseA;
import io.camunda.bizsol.bb.comm_agent.models.ObjectA;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class ServiceA {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public ServiceA(RestTemplate restTemplate, String externalApiBaseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = externalApiBaseUrl;
    }

    public String processA(ObjectA a) {
        // Call external REST endpoint
        String url = baseUrl + "/api/service-a?input=" + a.a();
        ApiResponseA response = restTemplate.getForObject(url, ApiResponseA.class);

        if (response != null && response.success()) {
            return a.a() + response.suffix();
        }
        throw new RuntimeException("Failed to process A via external API");
    }
}
