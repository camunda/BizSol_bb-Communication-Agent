package io.camunda.bizsol.bb.comm_agent.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import io.camunda.bizsol.bb.comm_agent.models.ApiResponseA;
import io.camunda.bizsol.bb.comm_agent.models.ObjectA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class ServiceATest {

    @Mock private RestTemplate restTemplate;

    private ServiceA serviceA;

    @BeforeEach
    void setUp() {
        serviceA = new ServiceA(restTemplate, "http://localhost:8089");
    }

    @Test
    void shouldProcessObjectA() {
        // given
        ObjectA objectA = new ObjectA("testValue");
        ApiResponseA mockResponse = new ApiResponseA(true, "_byServiceA_");
        when(restTemplate.getForObject(anyString(), eq(ApiResponseA.class)))
                .thenReturn(mockResponse);

        // when
        String result = serviceA.processA(objectA);

        // then
        assertThat(result).isEqualTo("testValue_byServiceA_");
    }

    @Test
    void shouldHandleEmptyString() {
        // given
        ObjectA objectA = new ObjectA("");
        ApiResponseA mockResponse = new ApiResponseA(true, "_byServiceA_");
        when(restTemplate.getForObject(anyString(), eq(ApiResponseA.class)))
                .thenReturn(mockResponse);

        // when
        String result = serviceA.processA(objectA);

        // then
        assertThat(result).isEqualTo("_byServiceA_");
    }
}
