# Persistence — DuckDB Job Worker

The `DuckDb` job worker exposes file-based [DuckDB](https://duckdb.org/) storage as a Camunda
service task. It provides a single `records` table with a generic JSON payload, keeping the worker
fully decoupled from any specific domain model.

## Table schema

```sql
CREATE TABLE records (
    id      VARCHAR PRIMARY KEY,
    payload TEXT           -- store any JSON string
);
```

## Configuration

```yaml
# application.yaml (main)
duckdb:
  file-path: ./data/comm-agent.db   # default; override per environment

# application.yaml (test) — in-memory, no file I/O
duckdb:
  file-path: ""
```

The parent directory is created automatically on startup if it does not exist.

---

## BPMN service task usage

**Job type:** `DuckDb`

### Input variables

| Variable        | Type            | Required for                    | Description                                                    |
|-----------------|-----------------|---------------------------------|----------------------------------------------------------------|
| `operation`     | String          | all                             | `CREATE`, `READ`, `UPDATE`, `DELETE`, `LIST` (case-insensitive) |
| `duckDbId`      | String          | CREATE / READ / UPDATE / DELETE | Primary key of the record                                      |
| `duckDbPayload` | String or Object | CREATE / UPDATE                | JSON string **or** a structured FEEL context — both accepted   |

### Output variables

All operations return a single variable `duckDbResult`. Its type depends on the operation.

| Operation | `duckDbResult` type | Value when record not found |
|-----------|--------------------|--------------------------|
| CREATE    | `{id: String, payload: Object}` | — (fails with incident) |
| READ      | `{id: String, payload: Object}` | `null` |
| UPDATE    | `{id: String, payload: Object}` | — (fails with incident) |
| DELETE    | `null`              | `null` (silent no-op) |
| LIST      | `List<{id: String, payload: Object}>` | `[]` (empty list) |

`payload` is a **parsed JSON object** — FEEL can navigate it directly:

```feel
duckDbResult.payload.name
for record in duckDbResult return record.payload.tier
```

---

## Operation reference

### CREATE

Inserts a new record. Fails with an incident if `duckDbId` already exists (primary key violation).

**Returns:** `duckDbResult` — the inserted record.

```xml
<zeebe:taskDefinition type="DuckDb" />
<zeebe:ioMapping>
  <zeebe:input source="=&quot;CREATE&quot;"         target="operation" />
  <zeebe:input source="=contactId"                  target="duckDbId" />
  <zeebe:input source="=contactPayload"             target="duckDbPayload" />
</zeebe:ioMapping>
<zeebe:output source="=duckDbResult"                target="savedContact" />
```

`duckDbPayload` accepts a FEEL context directly — no manual JSON serialisation needed:

```feel
= {name: "Acme Corp", email: "support@acme.com", tier: "premium"}
```

Example `duckDbResult` value in the process after the task:

```json
{
  "id": "contact-001",
  "payload": {"name": "Acme Corp", "email": "support@acme.com", "tier": "premium"}
}
```

---

### READ

Fetches one record by `duckDbId`.

**Returns:** `duckDbResult` — the record, or `null` if the id does not exist.

```xml
<zeebe:taskDefinition type="DuckDb" />
<zeebe:ioMapping>
  <zeebe:input source="=&quot;READ&quot;"           target="operation" />
  <zeebe:input source="=contactId"                  target="duckDbId" />
</zeebe:ioMapping>
<zeebe:output source="=duckDbResult"                target="contactRecord" />
```

Guard against the not-found case in FEEL:

```feel
if contactRecord = null then "unknown" else contactRecord.payload.name
```

---

### UPDATE

Overwrites the payload of an existing record. Fails with an incident if `duckDbId` is not found.

**Returns:** `duckDbResult` — the record with the new payload.

```xml
<zeebe:taskDefinition type="DuckDb" />
<zeebe:ioMapping>
  <zeebe:input source="=&quot;UPDATE&quot;"         target="operation" />
  <zeebe:input source="=contactId"                  target="duckDbId" />
  <zeebe:input source="=updatedPayload"             target="duckDbPayload" />
</zeebe:ioMapping>
<zeebe:output source="=duckDbResult"                target="updatedContact" />
```

---

### DELETE

Removes a record by `duckDbId`. Silently succeeds if the id does not exist.

**Returns:** `duckDbResult = null`, `duckDbResults = null`.

```xml
<zeebe:taskDefinition type="DuckDb" />
<zeebe:ioMapping>
  <zeebe:input source="=&quot;DELETE&quot;"         target="operation" />
  <zeebe:input source="=contactId"                  target="duckDbId" />
</zeebe:ioMapping>
```

No output mapping needed. The task produces no usable result.

---

### LIST

Returns every row in the table. `duckDbId` and `duckDbPayload` are ignored.

**Returns:** `duckDbResult` — a list of all records (empty list `[]` when table is empty).

```xml
<zeebe:taskDefinition type="DuckDb" />
<zeebe:ioMapping>
  <zeebe:input source="=&quot;LIST&quot;"           target="operation" />
</zeebe:ioMapping>
<zeebe:output source="=duckDbResult"               target="allContacts" />
```

Example `duckDbResult` value:

```json
[
  {"id": "contact-001", "payload": {"name": "Acme", "tier": "premium"}},
  {"id": "ticket-007",  "payload": {"subject": "Login broken", "priority": "high"}}
]
```

Iterate in a multi-instance subprocess or process in FEEL:

```feel
for record in allContacts return record.payload.name
```

---

## Different payload shapes per record

Because `payload` is plain text, records with completely different structures can coexist:

| `id`           | `payload`                                                      |
|----------------|----------------------------------------------------------------|
| `contact-001`  | `{"name":"Acme","phone":"+49 123 456"}`                       |
| `ticket-007`   | `{"subject":"Login broken","priority":"high","tags":["auth"]}` |
| `session-xyz`  | `{"startedAt":"2026-03-26T10:00Z","channel":"chat"}`           |
