# Camunda Business Solution: Communication Agent

This repository contains a sample implementation of a Communication Agent for multichannel customer interactions.

The Communication Agent receives inbound communication, maps it to individual customers, orchestrates downstream communication with specialist agents, and streamlines outbound communication back to the customer.


## What problem this building block solves

When communicating with customers, you may want to use various inbound and outbound channels together in a coherent fashion, e.g., exchanging emails about a topic and at the same time sending images and documents via a messaging service. This building block bundles all channels and provides a consistent and automated communication experience to the customer using Camunda's Agentic AI toolset.

## Where to use this building block

Use the Communication Agent when:
- You want to seamlessly interact with customers via various channels.
- You want to automatically include your specialized business processes and agents.

## How it works

The following overview shows the overall components of the Communication Agent building block:

![Component Overview](docs/component-overview.drawio.svg)

- A customer passes messages via various channels (e.g., SMS, phone, email, ...) to a Message Intake component.
- The messages are formatted in a uniform way independent of channel and forwarded to the Communication Agent.
- The Communication Agent uses Camunda's Agentic AI tooling to extract the intent of the customer message, and hand it over to specialized Business Agent(s).
- In this example, we included two sample agents: A Calculator Agent as well as a Translator Agent.
- Any reply by the Business Agents or the Communicaton Agent gets sent back to the customer via a Message Outbound process.

## How to run

### Set up a Camunda cluster

- **Camunda 8.8+** - e.g. [c8run](https://docs.camunda.io/docs/self-managed/setup/deploy/local/c8run/) for local development, or a [SaaS](https://docs.camunda.io/docs/guides/create-cluster/) / Self-Managed cluster

### Connect to your AI provider

In `communication-agent.bpmn` and in the two business agents (`business-agent-calculator.bpmn`, `business-agent-translator.bpmn`), you need to configure your AI provider in all AI tasks and sub-processes:

1. Open the specific diagram and select the AI task / AI sub-process.
2. Configure the **Model provider** section and...
   - add your provider details (e.g., provider `AWS Bedrock` in region `eu-west-1`), and
   - add your credentials via Connector secrets (e.g., `{{ secrets.AWS_BEDROCK_ACCESS_KEY }}` for your AWS Bedrock access key).
3. Choose a suitable model (e.g., `global.anthropic.claude-sonnet-4-5-20250929-v1:0` on AWS Bedrock).

### (Optional) Customize the process models to suit your needs

The building block is designed to be extended. The following sections describe the most common customizations you may want to apply before going to production.

#### Add custom input and output channels

`message-intake.bpmn` contains a group of start events — one per supported inbound channel (Email, SMS, Chat, and a generic message receiver for testing). Each start event is followed by a script task that normalizes the incoming payload into a uniform `messageContext` structure.

To add a new inbound channel (e.g., WhatsApp or a proprietary portal), add a new start event triggered by your channel's connector and a corresponding script task that maps the payload to the same `messageContext` format.

`message-outbound.bpmn` routes replies back to the customer via the channel stored in `customerContext.availableChannels`. It currently supports Email, SMS, and Chat. To add a new outbound channel, extend the gateway logic and add a corresponding send task using the appropriate connector.

#### Connect to your own customer database

By default, `message-intake.bpmn` resolves the customer identity using the `customer-database.dmn` decision table (invoked as a business rule task named *"Find customer context"*). The table returns a customer record including `id`, `name`, `availableChannels`, and per-channel contact details.

In production, replace this business rule task with a service task or connector that queries your actual customer database — for example via a REST connector, a SQL connector, or a custom job worker. The output must still provide the same fields so that downstream processes can route and correlate messages correctly.

#### Customize the Communication Agent behavior

The AI agent in `communication-agent.bpmn` uses a configurable system prompt to decide how to interact with the customer, when to delegate to a specialist business agent (Calculator or Translator), and when to conclude a session. Adapt this system prompt to reflect your organization's communication policy, tone of voice, escalation rules, and compliance requirements.

You can also control which business agents the Communication Agent can call by adding or removing the corresponding tool definitions and call activity connections (currently *"Forward to Calculator Agent"* and *"Forward to Translator Agent"*) within the process.

### Deploy all Camunda artifacts

The `/camunda-artifacts` directory contains a number of process models, all of which must be deployed:

| File | Purpose |
|------|---------|
| `communication-agent.bpmn` | Main communication orchestration process. |
| `message-intake.bpmn` | Inbound adapter process (email/webhook) that maps incoming communication and emits `CustomerCommunicationReceived`. |
| `message-outbound.bpmn` | Outbound communication subprocess for channel-specific customer replies. |
| `business-agent-calculator.bpmn` | Specialist sub-agent process for calculation tasks, triggered by communication orchestration. |
| `business-agent-translator.bpmn` | Specialist sub-agent process for translation tasks, triggered by communication orchestration. |
| `customer-database.dmn` | DMN decision table for customer database lookups. |

### Run a test process instance

Deploy and run any of the provided test diagrams found in `camunda-artifacts/test` and see it all in action!
