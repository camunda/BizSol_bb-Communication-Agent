package io.camunda.bizsol.bb.comm_agent.workers;

import io.camunda.bizsol.bb.comm_agent.models.ObjectA;
import io.camunda.bizsol.bb.comm_agent.models.ProcessVariables;
import io.camunda.bizsol.bb.comm_agent.services.ServiceA;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.VariablesAsType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class WorkerA {

    private static final Logger log = LoggerFactory.getLogger(WorkerA.class);
    private final ServiceA serviceA;

    public WorkerA(ServiceA serviceA) {
        this.serviceA = serviceA;
    }

    @JobWorker(type = "A")
    public ProcessVariables processA(@VariablesAsType ProcessVariables processVariables) {
        // Retrieve relevant process variables
        ObjectA a = processVariables.getA();
        log.info("Received A: {}", a);

        // call external service
        String aResult = serviceA.processA(a);

        // add to process variables for further processing
        processVariables.setConcatenatedResult(aResult);
        return processVariables;
    }
}
