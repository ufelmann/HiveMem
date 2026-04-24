# HiveMem API Refactor Design: `search` + `get_cell` Field Selection

## Goal

Refactor `search` and `get_cell` so callers can explicitly select returned fields via an `include` parameter. The change reduces unnecessary LLM context usage, especially by preventing `search` from returning raw `content` unless explicitly requested.

This refactor is a hard cut:

- No backward compatibility for old layer terminology
- No compatibility aliasing for legacy API field names
- No use of the legacy numeric layer labels anywhere in code, comments, tool descriptions, or documentation after the change

## Required Terminology

The semantic field names are:

- `content`: raw full text
- `summary`: concise summary text
- `key_points`: extracted list of points
- `insight`: higher-level conclusion

These names must be used consistently in:

- database column names, if any old layer names still exist
- Java code
- MCP tool descriptions and schemas
- JSON responses
- tests
- documentation

## Tool Contract

### Shared Rules

Both tools accept:

- `include?: string[]`

Allowed optional fields:

- `summary`
- `key_points`
- `insight`
- `content`
- `tags`
- `importance`
- `realm`
- `signal`
- `topic`
- `source`
- `created_at`
- `valid_from`
- `valid_until`

Always returned, regardless of `include`:

- `id`
- `realm`
- `signal`
- `topic`

Selection semantics:

- `include` omitted or `null`: use tool default
- `include: []`: return only `id`, `realm`, `signal`, `topic`
- duplicate values: allowed, deduplicated internally
- unknown values: reject with clear error
- non-array or non-string members: reject with clear error

Field presence semantics:

- fields not selected are omitted from the response object entirely
- selected fields are included even when the DB value is `null`
- this makes missing mean "not selected" and `null` mean "selected but empty"

### `search`

New parameter:

- `include?: string[]`

Default include when omitted or `null`:

- `["summary", "tags", "importance", "created_at"]`

Behavior:

- `content` must never appear unless explicitly requested
- metadata fields `id`, `realm`, `signal`, `topic` are always returned
- search ranking behavior stays unchanged

Example:

```json
{
  "query": "websocket",
  "include": ["summary", "content"]
}
```

### `get_cell`

New parameter:

- `include?: string[]`

Default include when omitted or `null`:

- `["summary", "key_points", "insight", "tags", "importance", "source", "created_at"]`

Behavior:

- metadata fields `id`, `realm`, `signal`, `topic` are always returned
- `content` is not part of the default and must be explicitly requested when raw text is needed

Example:

```json
{
  "cell_id": "uuid",
  "include": ["summary", "key_points"]
}
```

## Architecture

Use a shared field-selection layer for both read tools.

That layer owns:

- allowed field names
- required metadata fields
- default include list per tool
- deduplication
- validation
- mapping from API field name to SQL column name
- stable response field order

This avoids duplicated logic across handlers and repositories and keeps search/get-cell behavior aligned.

## Repository and SQL Design

## Mapping

The shared field-selection layer maps API names to SQL columns:

- `content -> content`
- `summary -> summary`
- `key_points -> key_points`
- `insight -> insight`
- `tags -> tags`
- `importance -> importance`
- `realm -> realm`
- `signal -> signal`
- `topic -> topic`
- `source -> source`
- `created_at -> created_at`
- `valid_from -> valid_from`
- `valid_until -> valid_until`

## `search`

The repository must build a projected `SELECT` list from:

- required metadata columns
- requested optional columns
- internal ranking-only columns required for the existing search algorithm

Rules:

- do not fetch `content` unless it is explicitly included
- do not fetch unused response columns
- internal ranking columns such as embeddings or popularity counters may still be selected when needed for scoring
- ranking logic must not depend on whether a response field is included

## `get_cell`

The repository must build a projected `SELECT` list from:

- required metadata columns
- requested optional columns

Rules:

- no `SELECT *`
- no eager loading of all fields when a narrower projection was requested

## Response Construction

Response objects are built from the normalized selected field list.

Output ordering should remain stable:

1. `id`
2. `realm`
3. `signal`
4. `topic`
5. selected optional fields in a fixed canonical order

This keeps responses predictable for callers and tests.

## Error Handling

Validation fails before SQL execution.

Expected failures:

- `Invalid include field: <name>` for unknown field names
- clear error for `include` not being an array
- clear error for non-string `include` items

The implementation should keep error text deterministic enough for integration tests.

## Tests

Add or update tests to cover:

- `search` without `include` returns required metadata plus search defaults
- `search` with `include: []` returns only required metadata
- `search` returns `content` only when explicitly included
- `get_cell` without `include` returns required metadata plus get-cell defaults without `content`
- `get_cell` with `include: []` returns only required metadata
- `get_cell` returns `content` only when explicitly included
- unknown include values fail clearly
- wrong include types fail clearly
- unselected fields are omitted
- selected nullable fields appear as `null` when empty
- repository-level search projection does not implicitly select `content`

## Documentation Changes

Update all exposed descriptions and docs to use semantic terminology only.

Required updates include:

- `SearchToolHandler` description and schema text
- `GetCellToolHandler` description and schema text
- README tool reference
- any docs or release notes that still mention the legacy numeric layer labels in relation to the public API or current implementation

The final codebase must not contain the legacy numeric layer labels anywhere.

## Scope Boundaries

In scope:

- Java server implementation
- MCP tool schemas and descriptions
- integration and repository tests
- documentation
- Flyway migration if old layer column names still exist in schema objects

Out of scope:

- changing search ranking behavior
- adding new API fields beyond the approved list
- transitional compatibility behavior

## Implementation Notes

- Search and get-cell should share the same include validation logic but keep separate defaults
- `realm`, `signal`, and `topic` may be listed in `include`, but this has no effect because they are always returned
- the implementation may normalize include values into an ordered set to preserve deterministic JSON output
- if the database already uses `content`, `summary`, `key_points`, and `insight`, no schema rename is needed for those columns; the hard cut still applies to code, tests, and documentation
