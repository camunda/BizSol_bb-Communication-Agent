# Camunda Business Solution Communication Agent (Sample)

This repository contains a sample implementation of a communication agent. It is meant as a starting point and must be
adapted to your customer context before use.

## How to Adapt the Sample

### 1) Inbound Handling (Channels and Mapping)

Adjust the inbound channel adapters (email, chat, etc.) to your customer's integration needs. Every inbound request must
be mapped to the common data structure in:

- `src/main/java/io/camunda/bizsol/bb/comm_agent/models/SupportCase.java`

The `SupportCase` fields (`subject`, `request`, `communicationContext`) are the canonical input for all downstream
processing. Ensure every channel maps its payload into this structure and provides a suitable `CommunicationContext`
implementation.

### 2) Case Matching (Customer Business Logic)

Implement customer-specific matching logic in the BPMN process:

- `camunda-artifacts/case-matching.bpmn`

The goal is to match the incoming `SupportCase` to an existing case when possible. Replace the sample logic with your
matching rules, data lookups, and decision points.

## Current Limitations

Several parts of the sample are not fully implemented yet. Treat them as placeholders and extend them as needed for your
customer project.

## Summary

1. Integrate inbound channels and map all inputs to `SupportCase`.
2. Implement customer-specific matching in `case-matching.bpmn`.
3. Complete the remaining placeholders to fit the target environment.
