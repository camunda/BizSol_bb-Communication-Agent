package io.camunda.bizsol.bb.comm_agent.models;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
@NoArgsConstructor
public class ProcessVariables {
    private ObjectA a;
    private String concatenatedResult;
}
