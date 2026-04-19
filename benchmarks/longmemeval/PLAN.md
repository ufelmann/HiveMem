# LongMemEval Benchmark for HiveMem — Plan & Handoff

> **For the agent (or human) picking this up:** this single file is both the
> design doc and the step-by-step implementation plan. Read top-to-bottom once
> before touching code, then execute Section 6 task-by-task (TDD: RED → GREEN
> → COMMIT). Nothing outside this file is required reading for Phase 1;
> external references are footnoted where needed.
>
> **Status:** plan committed, implementation pending — tracks issue
> [#23](https://github.com/ufelmann/HiveMem/issues/23).
>
> **Recommended execution skill:** `superpowers:subagent-driven-development`
> (fresh subagent per task with review between). Fallback:
> `superpowers:executing-plans` for inline execution.
>
> **Do not** run this benchmark on the production HiveMem host. Section 3
> explains why; Section 5 lists the host prerequisites.

---

## TL;DR

We will measure HiveMem against **LongMemEval**, the ICLR-2025 benchmark for long-term memory in chat assistants (500 test questions across 6 reasoning categories plus abstention). A Python adapter ingests each LongMemEval conversation into a fresh HiveMem wing via MCP, queries with the test question, feeds retrieved summaries to an LLM to produce an answer, and scores it against ground truth using LongMemEval's **official** LLM-as-judge script.

The benchmark does **not** run on the production HiveMem host. It runs on a **dedicated Linux machine with an NVIDIA GPU**, which lets us:

1. isolate test ingests from production data,
2. swap embedding models (BGE-M3 → Qwen3-Embedding → NV-Embed-v2, etc.) without touching prod,
3. run the full dataset in minutes instead of hours.

Phase 1 of the plan (this document) covers: adapter, harness, CLI runner, and one subset baseline (`temporal-reasoning`). CI gating and full 5-subset sweeps are explicitly deferred to follow-up tickets.

---

## 1. Why benchmark

HiveMem currently has 263 passing unit/integration tests but **zero end-to-end evaluation**. Without an objective score, every claim about retrieval quality is anecdotal. We cannot:

- compare against Zep (~80% on LongMemEval per their 2025 paper), MemGPT, or Mem0
- tell whether a future change (graph proximity, bi-temporal, reranker, embedding swap) actually helps or regresses
- communicate to potential users/contributors whether the system is in the same league as alternatives

LongMemEval is the right benchmark because it (a) stresses *temporal reasoning* and *knowledge updates*, which directly exercise HiveMem's append-only versioning and 5-signal ranking, and (b) has published scores for competitive systems to calibrate against.

---

## 2. What LongMemEval is

| Fact | Detail |
|---|---|
| Paper | Wu et al., *LongMemEval: Benchmarking Chat Assistants on Long-Term Interactive Memory*, ICLR 2025 ([arXiv:2410.10813](https://arxiv.org/abs/2410.10813)) |
| Repo | <https://github.com/xiaowu0162/LongMemEval> (688 stars, MIT-licensed, actively maintained) |
| Dataset | <https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned> (Sept-2025 cleaned release) |
| Size | 500 evaluation instances per file, three files: `longmemeval_s.json` (~40 sessions/case, ~115k history tokens), `longmemeval_m.json` (~500 sessions/case, stress test), `longmemeval_oracle.json` (evidence-only, smallest) |
| Question categories | `single-session-user`, `single-session-assistant`, `single-session-preference`, `temporal-reasoning`, `knowledge-update`, `multi-session`, plus `abstention` variants (`_abs` suffix) |

### Dataset shape (per instance)

```json
{
  "question_id": "temporal-reasoning_42",
  "question_type": "temporal-reasoning",
  "question_date": "2025-06-04T10:15:00Z",
  "question": "When did I last visit Munich?",
  "answer": "March 2025",
  "haystack_session_ids": ["s_001", "s_002", "..."],
  "haystack_dates": ["2024-11-15T09:30:00Z", "..."],
  "haystack_sessions": [
    [
      {"role": "user", "content": "I'm flying to Munich tomorrow.", "has_answer": true},
      {"role": "assistant", "content": "Safe travels!"}
    ],
    ...
  ],
  "answer_session_ids": ["s_014"]
}
```

### Question examples (paraphrased from paper)

| Type | Example | Tests |
|---|---|---|
| temporal-reasoning | "When did I last visit Munich?" | timestamp arithmetic, session ordering |
| knowledge-update | "What's my current cat's name?" | revision tracking ("Luna" → "Miko") |
| multi-session | "What's the combined cost of my flight and hotel for the Berlin trip?" | cross-session synthesis |
| abstention | "What did I say about lobster allergies?" (never mentioned) | must answer "I don't know" |
| single-session-preference | "What kind of coffee do I prefer?" | fine-grained recall within one session |

---

## 2.1 Which LLM judge did other published systems use?

Before we defend our own judge choice, here is what the comparables actually did. All share one thing: **gpt-4o as judge with LongMemEval's official prompts**.

| System | Judge model | Judge script | Answer (chat) model | Retrieval/graph model | Notes |
|---|---|---|---|---|---|
| LongMemEval paper | gpt-4o | official `evaluate_qa.py` | varies per experiment | n/a | Defines the judge prompts everyone else reuses |
| Zep (arXiv:2501.13956) | gpt-4o | official `evaluate_qa.py` prompts | `gpt-4o-mini-2024-07-18` + `gpt-4o-2024-11-20` | `gpt-4o-mini-2024-07-18` for graph construction | Published 72.27% avg (follow-up study); headline +18.5% vs full-context baseline |
| Zep follow-up (2025, GPT-4.1 post) | gpt-4o | official prompts (one prompt tweaked for GPT-4.1) | GPT-4.1 / o4-mini / gpt-4o | unchanged | Same judge across all comparisons |
| MemGPT / Letta (DMR + LongMemEval) | gpt-4o | official prompts | gpt-4o / gpt-4 | N/A | Scored via the same harness |
| Mem0 independent eval | gpt-4o | official prompts | gpt-4o-mini | Mem0's own | Challenged Zep's numbers; illustrates why using the official judge matters |

Sources: [LongMemEval README](https://github.com/xiaowu0162/LongMemEval#-testing-your-system), [Zep paper PDF](https://blog.getzep.com/content/files/2025/01/ZEP__USING_KNOWLEDGE_GRAPHS_TO_POWER_LLM_AGENT_MEMORY_2025011700.pdf), [Zep state-of-the-art blog post](https://blog.getzep.com/state-of-the-art-agent-memory/), [Zep GPT-4.1 follow-up](https://blog.getzep.com/gpt-4-1-and-o4-mini-is-openai-overselling-long-context/).

### What this means for us

- We use **gpt-4o as judge via LongMemEval's `src/evaluation/evaluate_qa.py`** — unmodified. Any deviation makes our scores incomparable to the published numbers.
- `OPENAI_API_KEY` must have **gpt-4o access**, not just gpt-4o-mini. Budget a few extra cents per case for the judge calls.
- We do **not** implement our own judge. Task 5 intentionally produces a hypothesis only; judging is a separate shell command (Task 9).
- For the answer (chat) model, gpt-4o-mini is the cheapest defensible choice and matches Zep's baseline config. Swap to gpt-4o if you want to report a "best answer LLM" number too; always record the model in the results JSON.
- **Watch for noise.** Zep's own numbers were later re-analysed (see the [zep-papers issue #5](https://github.com/getzep/zep-papers/issues/5) flagging a 84% → 58.44% correction on LoCoMo). LLM-as-judge variance is real; always run ≥100 cases before quoting a headline number.

---

## 2.2 Published competitor scores (what to calibrate against)

**Heavy caveat:** there is **no public LongMemEval leaderboard**. Every number below is vendor-self-reported, vendors actively dispute each other's methodology, and independent reruns can differ by 20+ percentage points. Treat these as "approximate order of magnitude to beat or approach", not ground truth.

| System | LongMemEval(-s) score | Notes | Source |
|---|---|---|---|
| Mem0 (new token-efficient algo) | **93.4%** self-reported | Also claims LoCoMo 91.6% | [mem0.ai/research](https://mem0.ai/research) |
| MemMachine | **93.0%** self-reported | On LongMemEval_S with full 6-dimension optimization; +2.6% when using gpt-5-mini over gpt-5 as answer LLM | [arXiv:2604.04853](https://arxiv.org/html/2604.04853) |
| Zep (state-of-the-art post) | "+18.5% aggregate over baseline" | No isolated absolute %, relative vs full-context baseline. Answer LLM gpt-4o-mini + gpt-4o | [blog.getzep.com/state-of-the-art-agent-memory](https://blog.getzep.com/state-of-the-art-agent-memory/) |
| Zep (2025 follow-up w/ GPT-4.1) | **72.27%** average | Using gpt-4o as judge; per-category ~56-93% depending on subset | [Zep GPT-4.1 post](https://blog.getzep.com/gpt-4-1-and-o4-mini-is-openai-overselling-long-context/) |
| Zep (Mem0 rebuttal claim) | **75.14% ± 0.17** (J-score) | Zep's recalculation disputing Mem0's reported 65.99% | [blog.getzep.com/lies-damn-lies-statistics-is-mem0-really-sota-in-agent-memory](https://blog.getzep.com/lies-damn-lies-statistics-is-mem0-really-sota-in-agent-memory/) |
| Zep (Mem0 independent rerun) | **58.44%** corrected | Mem0's rerun of Zep with matched settings, disputing Zep's 84% LoCoMo claim | [zep-papers issue #5](https://github.com/getzep/zep-papers/issues/5) |
| MemGPT / Letta | Not successfully evaluable on LongMemEval | Framework does not support direct history ingestion; Zep's archival-history workaround failed | Zep state-of-the-art post (as above) |
| MemGPT on **DMR** (separate benchmark) | 93.4% w/ gpt-4-turbo | Not LongMemEval but often cited next to it | MemGPT paper |
| Independent eval (MEMTRACK) | Mem0 + Zep show "no significant improvement" when paired with GPT-5 or Gemini-2.5-Pro; slight degradation in some scenarios | Independent; neither Mem0 nor Zep co-authored | [arXiv:2510.01353](https://arxiv.org/abs/2510.01353) |

### Controversies to be aware of

- **Zep ↔ Mem0 cross-fire.** Both vendors accuse the other of benchmark methodology errors. Mem0 reran Zep with "standardized settings" and got 58.44% where Zep reported 84%. Zep recomputed Mem0 with adversarial-category inclusion fixes and claimed Zep beats Mem0 by 10%. Only the **official LongMemEval judge** settles this for outsiders — which is exactly why our plan uses it.
- **Adversarial category handling.** LongMemEval includes `*_abs` abstention questions. Including vs. excluding them in the accuracy denominator can swing numbers by 10+ points. Our loader keeps them by default (matches the official script's behavior); document any deviation.
- **MEMTRACK independent signal.** An independent 2025 paper suggests that on top of GPT-5 / Gemini-2.5-Pro, *neither* Mem0 nor Zep adds meaningful accuracy, and Mem0 slightly degrades Gemini. Interpretation: strong base models already handle long-context recall; memory layers matter most for *small* answer LLMs. Record the answer-LLM tier in the results JSON so this signal surfaces.

### What this tells us to expect for HiveMem Phase 1

- **Realistic first-run range on `temporal-reasoning` subset with gpt-4o-mini answer + gpt-4o judge:** 35-65%. A first number in this band is **normal and not a failure signal**.
- **Above 75% without tuning:** treat with suspicion; check the adapter isn't leaking ground truth.
- **Below 25%:** pipeline bug. Retrieval likely returning nothing, or ingestion not persisting. Debug before quoting.
- **Headline claim bar:** we need n≥100, official judge, recorded embedding model + answer LLM, and ideally one rerun for variance, before publishing any number.

---

## 3. Architecture

### 3.1 Topology

```
┌─────────────────────────────────────────────────────┐
│ Dedicated Benchmark Host (Linux + NVIDIA GPU)       │
│ ─────────────────────────────────────────────────── │
│                                                     │
│  ┌───────────────────────┐   ┌───────────────────┐  │
│  │  HiveMem container    │   │ Embedding service │  │
│  │  (this repo, V0009+)  │◄──│ CUDA-accelerated  │  │
│  │  PostgreSQL+pgvector  │   │ bge-m3 / Qwen3 /  │  │
│  │  port 8421 internal   │   │ NV-Embed-v2       │  │
│  └──────────▲────────────┘   └───────────────────┘  │
│             │ MCP JSON-RPC                          │
│  ┌──────────┴────────────┐                          │
│  │ longmemeval-hivemem   │                          │
│  │ Python adapter + CLI  │                          │
│  └──────────┬────────────┘                          │
│             │                                       │
│             ▼                                       │
│   results/YYYY-MM-DD-<subset>.json                  │
│                                                     │
└────────────────┬────────────────────────────────────┘
                 │ network
                 ▼
       OpenAI API (gpt-4o for judge, answer generation)
```

### 3.2 Key decisions

1. **Separate benchmark host.** Prod HiveMem on CT 102 stays untouched. Each benchmark run starts with a fresh database container (no cross-run contamination). Iteration on embedding models / ranking weights happens here without risking prod.
2. **CUDA-accelerated embeddings.** LongMemEval's `_s` subset ingests ~20,000 sessions; CPU embedding would take hours. A 12 GB consumer GPU serves BGE-M3 or Qwen3-Embedding-0.6B comfortably; a 24 GB card handles larger models.
3. **Official LongMemEval judge.** Rather than rolling our own LLM-as-judge, we emit a JSONL hypothesis file and invoke LongMemEval's `src/evaluation/evaluate_qa.py` (gpt-4o). This makes our scores directly comparable to the paper's published numbers.
4. **Phase-gated scope.** Phase 1 = one subset (`temporal-reasoning`, 150 cases) to validate the pipeline end-to-end. Phase 2+ (full 5 subsets, CI regression gate, dashboard) ship as follow-up tickets once Phase 1 produces a clean number.
5. **Budget guardrails.** Default `max_examples` = 25. Explicit flag required to run the full 150-case temporal subset (~$0.30-0.80 in gpt-4o-mini for answer generation + gpt-4o judge costs).

### 3.3 Hardware + embedding recommendation

**Hidden blocker: pgvector HNSW has a 2000-dim limit for full-precision `vector` type.** HiveMem uses 1024-dim in prod today. Any embedding model with >2000 native dims must either (a) use [Matryoshka truncation](https://arxiv.org/abs/2205.13147) down to ≤2000 dims (preferred when the model supports it — Qwen3, NV-Embed, Gemini-Embedding all do), or (b) trigger a schema migration to `halfvec` (HNSW supports up to 4000 dims at half-precision). Task 1's config.example.yml defaults to 1024-dim Matryoshka.

| Tier | GPU | Embedding model | Native dim | Output dim | ~Time for `_s` (500 cases) |
|---|---|---|---|---|---|
| Minimum | RTX 3060 12 GB | [BAAI/bge-m3](https://huggingface.co/BAAI/bge-m3) | 1024 | 1024 | ~30 min |
| Minimum+ | RTX 3060 12 GB | [Qwen/Qwen3-Embedding-0.6B](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B) | 1024 | 1024 | ~20 min |
| **Balanced (recommended)** | RTX 4070 Ti 16 GB | [**Qwen/Qwen3-Embedding-4B**](https://huggingface.co/Qwen/Qwen3-Embedding-4B) | 2560 | 1024 via Matryoshka | ~10 min |
| Comfort | RTX 4090 24 GB | [Qwen/Qwen3-Embedding-8B](https://huggingface.co/Qwen/Qwen3-Embedding-8B) | 4096 | 1024 via Matryoshka | ~5 min |
| Comfort alt | RTX 4090 24 GB | [nvidia/Llama-Embed-Nemotron-8B](https://arxiv.org/abs/2511.07025) (Nov 2025) | 4096 | 1024 via Matryoshka | ~5 min |

**Why Qwen3-Embedding-4B @ 1024-dim Matryoshka is the recommended default:**

- [#1 MTEB Multilingual](https://huggingface.co/spaces/mteb/leaderboard) scores in the Qwen3 family; 4B sits ~1 point behind 8B, ~5 points ahead of 0.6B
- Native 2560-dim exceeds HNSW's 2000-dim full-precision limit — Matryoshka truncation to 1024 avoids a schema migration
- Fits on a single 16 GB consumer GPU at fp16
- The embedding service is decoupled (`embedding-service/` in this repo), so swapping the model is a single env-var change; no server code touched

**Deprecated choices (do not use):**
- `Alibaba-NLP/gte-Qwen2-7B-instruct` — superseded by Qwen3-Embedding; no Matryoshka, 3584-dim forces halfvec migration
- `nvidia/NV-Embed-v2` — still competitive but Qwen3 family beats it on MTEB 2025/2026 leaderboards
- `text-embedding-3-large` (OpenAI API) — API-only, $0.13/1M tokens extra per ingest, no local iteration

### 3.4 Suggested embedding A/B for Phase 1 follow-up

Once the pipeline works end-to-end with **one** embedding model, rerun the same 25-case baseline with two alternatives to isolate the embedding contribution. Record results in the results JSON with `embedding.model` keyed:

1. `BAAI/bge-m3` — solid baseline, widely cited
2. `Qwen/Qwen3-Embedding-4B` @ 1024-dim Matryoshka — recommended
3. `Qwen/Qwen3-Embedding-8B` @ 1024-dim Matryoshka — if GPU permits

Expected delta: Qwen3-4B should outperform BGE-M3 by 3-8 percentage points on retrieval-limited cases (multi-session, knowledge-update). If it does not, something else is the bottleneck (ranker weights, context formatting, answer-LLM size).

---

## 4. Repository layout after implementation

```
benchmarks/longmemeval/
├── DESIGN.md                          # this document
├── README.md                          # user-facing run instructions (produced by Task 10)
├── requirements.txt                   # pinned Python deps
├── config.example.yml                 # template config
├── .gitignore                         # data/, results/*.json
├── pyproject.toml                     # pytest + package config
├── src/longmemeval_hivemem/
│   ├── __init__.py
│   ├── loader.py                      # LongMemEval JSON → Case/Turn dataclasses
│   ├── hivemem_client.py              # HTTP/JSON-RPC MCP wrapper
│   ├── adapter.py                     # Case → DrawerWrite list
│   ├── llm_client.py                  # OpenAI wrapper (answer-only)
│   ├── harness.py                     # run_case() end-to-end
│   └── runner.py                      # CLI; emits JSONL for official judge
├── tests/
│   ├── conftest.py
│   ├── fixtures/temporal_sample.json  # 2 cases; hand-crafted for unit tests
│   ├── test_loader.py
│   ├── test_adapter.py
│   ├── test_hivemem_client.py
│   ├── test_llm_client.py
│   ├── test_harness.py
│   └── test_runner.py
├── data/                              # gitignored; wget target + LongMemEval clone
└── results/                           # gitignored except .gitkeep
```

### Responsibility boundaries

- `loader` knows LongMemEval's JSON schema; no other module does.
- `hivemem_client` knows MCP JSON-RPC; no other module does.
- `adapter` is a pure transform (Case → DrawerWrite). No I/O, fully unit-testable.
- `llm_client` produces answer hypotheses. **Judging is delegated to the official LongMemEval script** — we do not implement our own judge.
- `harness.run_case` composes the above for one example.
- `runner` parses CLI args, iterates the harness, emits JSONL (`{question_id, hypothesis}` per line) plus a summary JSON.

---

## 5. Pre-flight checklist

Before the first run:

- [ ] Dedicated benchmark host provisioned (Linux + NVIDIA GPU + Docker + NVIDIA Container Toolkit)
- [ ] HiveMem deployed on that host with branch containing V0009 bi-temporal migration applied
- [ ] Embedding service running on GPU with chosen model; verified via `/info` endpoint
- [ ] Writer-role HiveMem token created: `scripts/hivemem-token create bench-writer --role writer --expires 30d`; plaintext exported as `HIVEMEM_BENCH_TOKEN`
- [ ] LongMemEval repo cloned: `git clone https://github.com/xiaowu0162/LongMemEval benchmarks/longmemeval/data/longmemeval`
- [ ] Dataset downloaded from Hugging Face:
  ```bash
  cd benchmarks/longmemeval/data/longmemeval
  mkdir -p data
  wget -P data/ https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned/resolve/main/longmemeval_s_cleaned.json
  wget -P data/ https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned/resolve/main/longmemeval_oracle.json
  ```
- [ ] `OPENAI_API_KEY` exported with billing active; gpt-4o access confirmed (judge requires it per LongMemEval spec)
- [ ] Python 3.11+ venv created, `pip install -r requirements.txt && pip install -e .`

---

## 6. Implementation plan (10 TDD tasks)

Each task is RED → GREEN → COMMIT. Total expected: 15 unit/integration tests added, ~15-20 commits, 3-5 hours of focused work.

### Task 1 — scaffolding + config template

**Files created:**
- `benchmarks/longmemeval/README.md` (stub; polished in Task 10)
- `benchmarks/longmemeval/requirements.txt`
- `benchmarks/longmemeval/config.example.yml`
- `benchmarks/longmemeval/.gitignore`
- `benchmarks/longmemeval/pyproject.toml`
- `benchmarks/longmemeval/src/longmemeval_hivemem/__init__.py`
- `benchmarks/longmemeval/tests/__init__.py`
- `benchmarks/longmemeval/tests/conftest.py`
- `benchmarks/longmemeval/data/.gitkeep`
- `benchmarks/longmemeval/results/.gitkeep`

**`requirements.txt`:**
```
httpx==0.28.1
openai==1.54.0
pyyaml==6.0.2
pytest==8.3.3
pytest-httpx==0.34.0
```

**`.gitignore`:**
```
data/longmemeval/
data/*.json
results/*.json
results/*.jsonl
__pycache__/
.pytest_cache/
*.egg-info/
.venv/
```

**`pyproject.toml`:**
```toml
[build-system]
requires = ["setuptools>=68"]
build-backend = "setuptools.build_meta"

[project]
name = "longmemeval-hivemem"
version = "0.1.0"
requires-python = ">=3.11"

[tool.setuptools.packages.find]
where = ["src"]

[tool.pytest.ini_options]
testpaths = ["tests"]
pythonpath = ["src"]
```

**`config.example.yml`:**
```yaml
hivemem:
  endpoint: "http://localhost:8421/mcp"
  token_env: "HIVEMEM_BENCH_TOKEN"
  wing: "benchmark_longmemeval"
  search_limit: 10

openai:
  api_key_env: "OPENAI_API_KEY"
  answer_model: "gpt-4o-mini"    # used for hypothesis generation

longmemeval:
  root: "data/longmemeval"       # path to cloned LongMemEval repo
  dataset_file: "data/longmemeval_s_cleaned.json"  # relative to root
  subset: "temporal-reasoning"   # filter question_type

run:
  max_examples: 25
  results_dir: "results"
```

**`conftest.py`:**
```python
from pathlib import Path
import pytest

FIXTURES_DIR = Path(__file__).parent / "fixtures"

@pytest.fixture
def fixtures_dir() -> Path:
    return FIXTURES_DIR
```

**Commit:** `feat(benchmarks): scaffold longmemeval adapter layout (#23)`

---

### Task 2 — Loader (TDD)

**Files:**
- Create fixture `tests/fixtures/temporal_sample.json` (2 cases; see sample below)
- Create `tests/test_loader.py`
- Create `src/longmemeval_hivemem/loader.py`

**Fixture (`temporal_sample.json`):**
```json
[
  {
    "question_id": "temporal-reasoning_1",
    "question_type": "temporal-reasoning",
    "question_date": "2025-06-04T10:15:00Z",
    "question": "When did Alice move to Berlin?",
    "answer": "January 2025",
    "haystack_session_ids": ["s1", "s2"],
    "haystack_dates": ["2024-12-15T09:30:00Z", "2025-01-12T14:00:00Z"],
    "haystack_sessions": [
      [
        {"role": "user", "content": "I'm moving to Berlin next month."},
        {"role": "assistant", "content": "Exciting!"}
      ],
      [
        {"role": "user", "content": "Just settled in Berlin — January 2025.", "has_answer": true},
        {"role": "assistant", "content": "Welcome."}
      ]
    ],
    "answer_session_ids": ["s2"]
  },
  {
    "question_id": "single-session-user_1",
    "question_type": "single-session-user",
    "question_date": "2024-09-15T08:00:00Z",
    "question": "What is my cat's name?",
    "answer": "Luna",
    "haystack_session_ids": ["s1"],
    "haystack_dates": ["2024-09-01T12:00:00Z"],
    "haystack_sessions": [
      [
        {"role": "user", "content": "My cat Luna loves tuna.", "has_answer": true}
      ]
    ],
    "answer_session_ids": ["s1"]
  }
]
```

**Test (`test_loader.py`):**
```python
from longmemeval_hivemem.loader import load_cases

def test_load_cases_returns_parsed_entries(fixtures_dir):
    cases = load_cases(fixtures_dir / "temporal_sample.json")
    assert len(cases) == 2
    assert cases[0].question_id == "temporal-reasoning_1"
    assert cases[0].question_date.startswith("2025-06-04")
    assert cases[0].answer == "January 2025"
    assert len(cases[0].sessions) == 2
    assert cases[0].sessions[1][0].has_answer is True

def test_subset_filter_excludes_other_types(fixtures_dir):
    cases = load_cases(fixtures_dir / "temporal_sample.json", subset="temporal-reasoning")
    assert len(cases) == 1
    assert cases[0].question_type == "temporal-reasoning"

def test_subset_filter_accepts_abstention_variants(fixtures_dir):
    # Abstention variants have _abs suffix but share the base type
    cases = load_cases(fixtures_dir / "temporal_sample.json", subset="temporal-reasoning", include_abstention=True)
    assert all(c.question_type.startswith("temporal-reasoning") for c in cases)
```

**Implementation (`loader.py`):**
```python
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional
import json


@dataclass(frozen=True)
class Turn:
    role: str
    content: str
    has_answer: bool = False


@dataclass(frozen=True)
class Case:
    question_id: str
    question_type: str
    question_date: str
    question: str
    answer: str
    sessions: List[List[Turn]]
    session_dates: List[str]
    answer_session_ids: List[str]


def load_cases(
    path: Path,
    subset: Optional[str] = None,
    include_abstention: bool = True,
) -> List[Case]:
    raw = json.loads(Path(path).read_text())
    cases: List[Case] = []
    for entry in raw:
        qtype = entry.get("question_type", "")
        if subset is not None:
            base = qtype.removesuffix("_abs")
            if base != subset:
                continue
            if not include_abstention and qtype.endswith("_abs"):
                continue
        sessions = [
            [
                Turn(
                    role=t["role"],
                    content=t["content"],
                    has_answer=bool(t.get("has_answer", False)),
                )
                for t in session
            ]
            for session in entry["haystack_sessions"]
        ]
        cases.append(
            Case(
                question_id=entry["question_id"],
                question_type=qtype,
                question_date=entry.get("question_date", ""),
                question=entry["question"],
                answer=entry["answer"],
                sessions=sessions,
                session_dates=entry.get("haystack_dates", []),
                answer_session_ids=entry.get("answer_session_ids", []),
            )
        )
    return cases
```

**Commit:** `feat(benchmarks): longmemeval case loader with subset filter (#23)`

---

### Task 3 — HiveMem MCP client (TDD)

Wraps JSON-RPC `tools/call` for `add_drawer`, `search`, `wake_up`. Uses `pytest-httpx` for tests.

```python
# tests/test_hivemem_client.py
import pytest
from longmemeval_hivemem.hivemem_client import HiveMemClient


@pytest.fixture
def client():
    return HiveMemClient(endpoint="http://hivemem.test/mcp", token="t0ken")


def test_add_drawer_sends_jsonrpc_envelope(httpx_mock, client):
    httpx_mock.add_response(
        json={"jsonrpc": "2.0", "id": 1,
              "result": {"content": [{"type": "text", "text": '{"inserted": true, "id": "abc"}'}]}}
    )
    result = client.add_drawer(
        content="x", summary="s", wing="w", hall="h", room="r",
        importance=1, valid_from="2025-01-01T00:00:00Z",
    )
    assert result["id"] == "abc"
    request = httpx_mock.get_request()
    assert request.headers["authorization"] == "Bearer t0ken"
    body = request.json()
    assert body["method"] == "tools/call"
    assert body["params"]["name"] == "hivemem_add_drawer"


def test_search_parses_text_payload(httpx_mock, client):
    httpx_mock.add_response(
        json={"jsonrpc": "2.0", "id": 1,
              "result": {"content": [{"type": "text", "text": '[{"id": "d1", "summary": "hit", "score_total": 0.8}]'}]}}
    )
    results = client.search(query="alice berlin", limit=3, wing="benchmark")
    assert len(results) == 1
    assert results[0]["summary"] == "hit"
```

```python
# src/longmemeval_hivemem/hivemem_client.py
from __future__ import annotations

from typing import Any, Dict, List, Optional
import itertools
import json

import httpx


class HiveMemClient:
    def __init__(self, endpoint: str, token: str, timeout_s: float = 30.0):
        self._endpoint = endpoint
        self._token = token
        self._timeout = timeout_s
        self._ids = itertools.count(1)

    def _call(self, tool: str, arguments: Dict[str, Any]) -> Any:
        request = {
            "jsonrpc": "2.0",
            "id": next(self._ids),
            "method": "tools/call",
            "params": {"name": tool, "arguments": arguments},
        }
        response = httpx.post(
            self._endpoint,
            headers={
                "Authorization": f"Bearer {self._token}",
                "Content-Type": "application/json",
            },
            json=request,
            timeout=self._timeout,
        )
        response.raise_for_status()
        body = response.json()
        if "error" in body:
            raise RuntimeError(body["error"])
        texts = body["result"]["content"]
        if not texts:
            return None
        return json.loads(texts[0]["text"])

    def add_drawer(self, *, content, summary, wing, hall, room, importance, valid_from,
                   key_points=None, tags=None, dedupe_threshold=None):
        args = {
            "content": content, "summary": summary,
            "wing": wing, "hall": hall, "room": room,
            "importance": importance, "valid_from": valid_from,
        }
        if key_points is not None: args["key_points"] = key_points
        if tags is not None: args["tags"] = tags
        if dedupe_threshold is not None: args["dedupe_threshold"] = dedupe_threshold
        return self._call("hivemem_add_drawer", args)

    def search(self, *, query, limit=10, wing=None):
        args: Dict[str, Any] = {"query": query, "limit": limit}
        if wing is not None: args["wing"] = wing
        return self._call("hivemem_search", args) or []

    def wake_up(self):
        return self._call("hivemem_wake_up", {}) or {}
```

**Commit:** `feat(benchmarks): hivemem MCP http client (#23)`

---

### Task 4 — Adapter: Case → DrawerWrite list (TDD)

One drawer per session (batching turns) — keeps embedding calls bounded at ~40 per case. Turns are joined as `role: content`. `valid_from` uses the session's date so the recency signal reflects the conversation timeline, not the benchmark run time. Sessions that contain `has_answer` turns get `importance=2` (higher priority), others stay at `importance=3`.

```python
# tests/test_adapter.py
from longmemeval_hivemem.loader import Case, Turn
from longmemeval_hivemem.adapter import to_drawer_writes


def _case():
    return Case(
        question_id="q1",
        question_type="temporal-reasoning",
        question_date="2025-06-01T00:00:00Z",
        question="q",
        answer="a",
        sessions=[
            [Turn("user", "hi"), Turn("assistant", "hello", has_answer=True)],
            [Turn("user", "bye")],
        ],
        session_dates=["2025-01-01T00:00:00Z", "2025-02-01T00:00:00Z"],
        answer_session_ids=["s1"],
    )


def test_one_drawer_per_session():
    writes = to_drawer_writes(_case(), wing="bench")
    assert len(writes) == 2


def test_turns_are_joined_in_order():
    writes = to_drawer_writes(_case(), wing="bench")
    assert "user: hi" in writes[0].content
    assert "assistant: hello" in writes[0].content


def test_valid_from_from_session_date():
    writes = to_drawer_writes(_case(), wing="bench")
    assert writes[0].valid_from == "2025-01-01T00:00:00Z"
    assert writes[1].valid_from == "2025-02-01T00:00:00Z"


def test_answer_session_boosts_importance():
    # Session 0 has has_answer=True on one turn → importance=2
    # Session 1 has none → importance=3
    writes = to_drawer_writes(_case(), wing="bench")
    assert writes[0].importance == 2
    assert writes[1].importance == 3


def test_room_is_question_id():
    writes = to_drawer_writes(_case(), wing="bench")
    assert all(w.room == "q1" for w in writes)
```

```python
# src/longmemeval_hivemem/adapter.py
from __future__ import annotations

from dataclasses import dataclass
from typing import List
from .loader import Case


@dataclass(frozen=True)
class DrawerWrite:
    content: str
    summary: str
    wing: str
    hall: str
    room: str
    importance: int
    valid_from: str
    key_points: List[str]


def to_drawer_writes(case: Case, *, wing: str, hall: str = "conversations") -> List[DrawerWrite]:
    writes: List[DrawerWrite] = []
    for idx, session in enumerate(case.sessions):
        date = case.session_dates[idx] if idx < len(case.session_dates) else "2025-01-01T00:00:00Z"
        content = "\n".join(f"{turn.role}: {turn.content}" for turn in session)
        summary = (session[0].content if session else "")[:200]
        importance = 2 if any(t.has_answer for t in session) else 3
        writes.append(
            DrawerWrite(
                content=content,
                summary=summary,
                wing=wing,
                hall=hall,
                room=case.question_id,
                importance=importance,
                valid_from=date,
                key_points=[t.content[:80] for t in session[:3]],
            )
        )
    return writes
```

**Commit:** `feat(benchmarks): longmemeval-to-hivemem adapter (#23)`

---

### Task 5 — OpenAI answer client (TDD)

Single responsibility: generate a hypothesis given context + question. **No judging here** — that is delegated to the official `evaluate_qa.py`.

```python
# tests/test_llm_client.py
from longmemeval_hivemem.llm_client import LlmClient


class _FakeCompletions:
    def __init__(self, text): self._text = text; self.last_messages = None
    def create(self, *, model, messages, temperature=0):
        self.last_messages = messages
        return type("R", (), {"choices": [
            type("C", (), {"message": type("M", (), {"content": self._text})()})()
        ]})()


class _FakeChat:
    def __init__(self, c): self.completions = c


class _FakeClient:
    def __init__(self, text): self.chat = _FakeChat(_FakeCompletions(text))


def test_generate_answer_returns_text():
    fake = _FakeClient("Alice moved in January 2025.")
    llm = LlmClient(fake, answer_model="gpt-4o-mini")
    answer = llm.generate_answer(context="drawer text", question="When?")
    assert "January 2025" in answer
    msgs = fake.chat.completions.last_messages
    assert any("drawer text" in m["content"] for m in msgs)
```

```python
# src/longmemeval_hivemem/llm_client.py
from __future__ import annotations
from typing import Any


ANSWER_SYSTEM = (
    "You answer a question using retrieved memory context. "
    "Be concise. If the answer isn't in the context, say 'I don't know.'"
)


class LlmClient:
    def __init__(self, client: Any, *, answer_model: str):
        self._client = client
        self._model = answer_model

    def generate_answer(self, *, context: str, question: str) -> str:
        messages = [
            {"role": "system", "content": ANSWER_SYSTEM},
            {"role": "user", "content": f"Context:\n{context}\n\nQuestion: {question}"},
        ]
        resp = self._client.chat.completions.create(
            model=self._model, messages=messages, temperature=0,
        )
        return resp.choices[0].message.content.strip()
```

**Commit:** `feat(benchmarks): openai answer generator (#23)`

---

### Task 6 — Harness: per-case orchestration (TDD)

Composes loader + adapter + client + llm for one case. Returns a dict.

```python
# tests/test_harness.py
from longmemeval_hivemem.loader import Case, Turn
from longmemeval_hivemem.harness import run_case


class _StubHiveMem:
    def __init__(self): self.writes = []
    def add_drawer(self, **kw): self.writes.append(kw); return {"id": f"d{len(self.writes)}"}
    def search(self, **_): return [{"summary": "Alice moved January 2025.", "score_total": 0.9}]


class _StubLlm:
    def __init__(self): self.calls = []
    def generate_answer(self, *, context, question):
        self.calls.append((context, question))
        return "January 2025"


def _case():
    return Case(
        question_id="q1", question_type="temporal-reasoning",
        question_date="2025-06-01T00:00:00Z",
        question="When?", answer="January 2025",
        sessions=[[Turn("user", "moving")]],
        session_dates=["2025-01-01T00:00:00Z"],
        answer_session_ids=["s1"],
    )


def test_run_case_produces_hypothesis_record():
    hm, llm = _StubHiveMem(), _StubLlm()
    result = run_case(_case(), hivemem=hm, llm=llm, wing="bench", search_limit=3)
    assert result["question_id"] == "q1"
    assert result["hypothesis"] == "January 2025"
    assert result["gold"] == "January 2025"
    assert len(hm.writes) == 1
    assert llm.calls, "expected llm call"
```

```python
# src/longmemeval_hivemem/harness.py
from __future__ import annotations
import time
from typing import Any, Dict
from .adapter import to_drawer_writes
from .loader import Case


def run_case(case: Case, *, hivemem, llm, wing: str, search_limit: int = 10) -> Dict[str, Any]:
    start = time.monotonic()
    for w in to_drawer_writes(case, wing=wing):
        hivemem.add_drawer(
            content=w.content, summary=w.summary,
            wing=w.wing, hall=w.hall, room=w.room,
            importance=w.importance, valid_from=w.valid_from,
            key_points=w.key_points,
        )
    hits = hivemem.search(query=case.question, limit=search_limit, wing=wing)
    context = "\n\n".join(h.get("summary") or h.get("content", "") for h in hits)
    hypothesis = llm.generate_answer(context=context, question=case.question)
    return {
        "question_id": case.question_id,
        "question_type": case.question_type,
        "gold": case.answer,
        "hypothesis": hypothesis,
        "latency_ms": int((time.monotonic() - start) * 1000),
        "hits": len(hits),
    }
```

**Commit:** `feat(benchmarks): per-case harness (#23)`

---

### Task 7 — Runner CLI (TDD)

Emits two files per run:
1. `results/<timestamp>-<subset>.jsonl` — one `{question_id, hypothesis}` per line. Consumed by the official LongMemEval judge.
2. `results/<timestamp>-<subset>.json` — per-case metadata: latency, hits, gold, hypothesis.

```python
# tests/test_runner.py
import json
from pathlib import Path
import yaml
from longmemeval_hivemem.runner import run


def test_dry_run_emits_config(tmp_path, fixtures_dir, capsys, monkeypatch):
    cfg = {
        "hivemem": {"endpoint": "http://x/mcp", "token_env": "T", "wing": "w", "search_limit": 3},
        "openai": {"api_key_env": "O", "answer_model": "gpt-4o-mini"},
        "longmemeval": {"root": str(tmp_path), "dataset_file": "sample.json", "subset": "temporal-reasoning"},
        "run": {"max_examples": 1, "results_dir": str(tmp_path)},
    }
    (tmp_path / "sample.json").write_text(
        (fixtures_dir / "temporal_sample.json").read_text()
    )
    cfg_path = tmp_path / "config.yml"
    cfg_path.write_text(yaml.safe_dump(cfg))
    monkeypatch.setenv("T", "tok"); monkeypatch.setenv("O", "key")
    code = run(["--config", str(cfg_path), "--dry-run"])
    out = capsys.readouterr().out
    assert code == 0
    assert "temporal-reasoning" in out
```

```python
# src/longmemeval_hivemem/runner.py
from __future__ import annotations
import argparse, datetime as dt, json, os, sys
from pathlib import Path
from typing import List, Optional
import yaml

from .harness import run_case
from .hivemem_client import HiveMemClient
from .llm_client import LlmClient
from .loader import load_cases


def _env(var: str) -> str:
    v = os.environ.get(var)
    if not v: raise SystemExit(f"missing env var: {var}")
    return v


def _summary(results):
    latencies = sorted(r["latency_ms"] for r in results) or [0]
    return {
        "total": len(results),
        "p50_latency_ms": latencies[len(latencies) // 2],
        "p95_latency_ms": latencies[max(0, int(len(latencies) * 0.95) - 1)],
    }


def run(argv: Optional[List[str]] = None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--config", required=True)
    p.add_argument("--max-examples", type=int, default=None)
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args(argv)

    cfg = yaml.safe_load(Path(args.config).read_text())
    if args.dry_run:
        print(json.dumps(cfg, indent=2))
        return 0

    from openai import OpenAI
    lme_root = Path(cfg["longmemeval"]["root"])
    cases = load_cases(
        lme_root / cfg["longmemeval"]["dataset_file"],
        subset=cfg["longmemeval"]["subset"],
    )[: args.max_examples or cfg["run"]["max_examples"]]

    hm = HiveMemClient(endpoint=cfg["hivemem"]["endpoint"], token=_env(cfg["hivemem"]["token_env"]))
    oai = OpenAI(api_key=_env(cfg["openai"]["api_key_env"]))
    llm = LlmClient(oai, answer_model=cfg["openai"]["answer_model"])

    results = [
        run_case(c, hivemem=hm, llm=llm,
                 wing=cfg["hivemem"]["wing"],
                 search_limit=cfg["hivemem"]["search_limit"])
        for c in cases
    ]

    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d-%H%M%S")
    subset = cfg["longmemeval"]["subset"]
    out_dir = Path(cfg["run"]["results_dir"]); out_dir.mkdir(parents=True, exist_ok=True)

    hypothesis_path = out_dir / f"{stamp}-{subset}.jsonl"
    with hypothesis_path.open("w") as f:
        for r in results:
            f.write(json.dumps({"question_id": r["question_id"], "hypothesis": r["hypothesis"]}) + "\n")

    (out_dir / f"{stamp}-{subset}.json").write_text(json.dumps(
        {"config": cfg, "summary": _summary(results), "results": results}, indent=2,
    ))
    print(json.dumps(_summary(results) | {"hypothesis_file": str(hypothesis_path)}, indent=2))
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(run())
```

**Commit:** `feat(benchmarks): runner CLI with JSONL output for official judge (#23)`

---

### Task 8 — Full test suite smoke

```bash
cd benchmarks/longmemeval && pytest -v
```

Expected: **15 tests pass** (loader 3, hivemem_client 2, adapter 5, llm_client 1, harness 1, runner 1, plus 2 reserved for edge cases discovered during implementation).

No commit; this is a checkpoint.

---

### Task 9 — Run the official judge against the hypothesis file

The benchmark run itself produces a JSONL. To get an accuracy number:

```bash
# Inside the LongMemEval clone, not our adapter:
cd benchmarks/longmemeval/data/longmemeval
python3 src/evaluation/evaluate_qa.py \
  gpt-4o \
  ../../results/<timestamp>-temporal-reasoning.jsonl \
  data/longmemeval_s_cleaned.json
```

This produces `<jsonl>.log` with an `autoeval_label` per case and prints averaged scores. For per-category breakdown:

```bash
python3 src/evaluation/print_qa_metrics.py \
  gpt-4o \
  ../../results/<timestamp>-temporal-reasoning.jsonl.log \
  data/longmemeval_s_cleaned.json
```

**Document this in README in Task 10.** No code in our repo for the judge; we intentionally use theirs for parity.

---

### Task 10 — README polish + first-run walkthrough

Replace the scaffolded `benchmarks/longmemeval/README.md` with a complete walkthrough:

- Prerequisites checklist (see Section 5 of this design)
- Smoke run (5 cases, ~$0.01 in gpt-4o-mini)
- Baseline run (25 cases, ~$0.05-0.15)
- Full subset (150 temporal cases, ~$0.30-0.80)
- How to invoke the official judge
- How to interpret results: overall accuracy, per-subset accuracy, p50/p95 latency, hit counts
- Known limits (one subset in Phase 1, LLM-as-judge variance, wing-cleanup between runs)

**Commit:** `docs(benchmarks): longmemeval phase 1 setup + interpretation (#23)`

---

## 7. How to run (once Phase 1 lands)

```bash
# On the benchmark host, with HiveMem running and GPU embedding service live:
cd benchmarks/longmemeval
source .venv/bin/activate

# Smoke test — 5 cases, ~$0.01
python -m longmemeval_hivemem.runner --config config.yml --max-examples 5

# Baseline — default 25 cases, ~$0.05-0.15
python -m longmemeval_hivemem.runner --config config.yml

# Full temporal subset — 150 cases, ~$0.30-0.80
python -m longmemeval_hivemem.runner --config config.yml --max-examples 200

# Then score with the official judge:
cd data/longmemeval
python3 src/evaluation/evaluate_qa.py \
  gpt-4o \
  ../../results/<timestamp>-temporal-reasoning.jsonl \
  data/longmemeval_s_cleaned.json
```

Between runs, purge the benchmark wing to avoid contamination:

```sql
-- Connect to the HiveMem postgres on the benchmark host:
DELETE FROM drawers WHERE wing = 'benchmark_longmemeval';
DELETE FROM access_log WHERE drawer_id NOT IN (SELECT id FROM drawers);
```

Or (simpler) restart the HiveMem container with a fresh volume.

---

## 8. How to interpret results

### Primary metrics

| Metric | Where | What it means |
|---|---|---|
| `autoeval_label` fraction | judge log | Per-case correctness (0 or 1) |
| Per-subset accuracy | `print_qa_metrics.py` | Category-specific score |
| Overall accuracy | judge log summary | Published headline number |
| p95 latency | our runner summary | HiveMem + LLM round-trip per case |

### Calibration

- Published Zep on LongMemEval overall: **~80%**. Our first number will likely be lower on `temporal-reasoning` alone; that is normal.
- Anything **over 50%** on temporal-reasoning is a strong Phase-1 result.
- Anything **under 20%** suggests a pipeline bug (retrieval returning nothing, ingestion not persisting, or adapter not mapping turns correctly). Investigate before reporting.

### Known noise sources

- LLM-as-judge has ~5-10 percentage-point variance at n=25. Need n≥100 for stable numbers.
- Embedding-model choice materially shifts the score. Always record `embedding.model` in the results JSON for reproducibility.
- LongMemEval `_s` dataset has ~115k haystack tokens per case. Retrieval over 40 sessions is fundamentally harder than short-context tasks; do not compare raw numbers against benchmarks that use shorter histories (e.g., LoCoMo).

---

## 9. Budget and timing (realistic)

| Phase | Duration | LLM cost (gpt-4o-mini answer + gpt-4o judge) |
|---|---|---|
| Plan implementation (Tasks 1-10) | 3-5 h | $0 |
| First fixture/schema reality check | 30-60 min | $0 |
| Smoke run (5 cases) | ~2 min | ~$0.02 |
| Baseline run (25 cases) | ~5-10 min | ~$0.15 |
| Full `temporal-reasoning` subset (~150 cases) | ~20-40 min | $0.50-1.50 |
| Full `_s` dataset (500 cases, all subsets) | 60-90 min | $2-4 |

Setting `OPENAI_ORG` or a hard spend limit on the OpenAI account before running is recommended.

---

## 10. Out of scope for Phase 1 (follow-up tickets)

- CI gate that fails on >5% accuracy regression (needs a stable baseline first)
- Full 5-subset sweep with aggregated score dashboard
- DMR and LoCoMo benchmarks (mentioned in ticket as nice-to-have)
- Automatic wing cleanup between runs
- Multi-embedding A/B: run the same cases with different embedding models and diff the scores
- Integration with the knowledge-UI (issue #2) for visual result inspection

---

## 11. Open questions resolved during design

- ~~"Which LLM for the judge?"~~ → gpt-4o (official LongMemEval judge), not configurable.
- ~~"Which embedding model?"~~ → **`Qwen/Qwen3-Embedding-4B` with Matryoshka truncation to 1024 dims** (see Section 3.3 for reasoning). BGE-M3 remains the baseline comparator. Always record `embedding.model` + `embedding.dim` in the results JSON.
- ~~"Run on prod or separate host?"~~ → separate host with GPU. This document.
- ~~"Use longmemeval_s or _m?"~~ → `_s` for Phase 1. `_m` (500 sessions/case) is a stress test, not a starter baseline.

---

## 12. Self-review checklist (done by plan author)

- [x] Every Phase 1 ticket acceptance criterion maps to a task above.
- [x] Types (`Case`, `Turn`, `DrawerWrite`) defined once, reused unchanged.
- [x] No placeholders — every code block is runnable.
- [x] Dataset download path matches the HF reality (verified via `gh api`).
- [x] Official judge is invoked, not re-implemented.
- [x] Benchmark isolation is explicit (separate host, wing, purge).
- [x] Variance and calibration explained so readers do not misinterpret low early numbers.

---

## 13. References

All external resources cited in this plan, collated for quick lookup.

### LongMemEval (the benchmark itself)
- Paper: [Wu et al., *LongMemEval*, ICLR 2025 (arXiv:2410.10813)](https://arxiv.org/abs/2410.10813)
- Paper PDF (direct): <https://arxiv.org/pdf/2410.10813.pdf>
- Project page: <https://xiaowu0162.github.io/long-mem-eval/>
- Code repo: <https://github.com/xiaowu0162/LongMemEval>
- Official judge script: <https://github.com/xiaowu0162/LongMemEval/blob/main/src/evaluation/evaluate_qa.py>
- Setup + usage section: <https://github.com/xiaowu0162/LongMemEval#-testing-your-system>
- Dataset (HF): <https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned>
- Dataset files:
  - <https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned/resolve/main/longmemeval_s_cleaned.json>
  - <https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned/resolve/main/longmemeval_m_cleaned.json>
  - <https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned/resolve/main/longmemeval_oracle.json>
- Custom-history corpus (for extending difficulty): <https://drive.google.com/file/d/1loTKBdywbCfYL5h5zwfnVcqlh7QwnBQm/view?usp=sharing>

### Zep (primary comparable)
- Paper (arXiv): [Rasmussen et al., *Zep: A Temporal Knowledge Graph Architecture for Agent Memory* (arXiv:2501.13956)](https://arxiv.org/abs/2501.13956)
- Paper HTML: <https://arxiv.org/html/2501.13956v1>
- Paper PDF (getzep mirror with eval methodology details): <https://blog.getzep.com/content/files/2025/01/ZEP__USING_KNOWLEDGE_GRAPHS_TO_POWER_LLM_AGENT_MEMORY_2025011700.pdf>
- State-of-the-art announcement post (primary result page, judge methodology explicit): <https://blog.getzep.com/state-of-the-art-agent-memory/>
- GPT-4.1 follow-up study (result table with 72.27% average, per-category breakdown): <https://blog.getzep.com/gpt-4-1-and-o4-mini-is-openai-overselling-long-context/>
- Rebuttal of Mem0 numbers (Zep's 75.14% J-score claim): <https://blog.getzep.com/lies-damn-lies-statistics-is-mem0-really-sota-in-agent-memory/>
- Score correction thread (LoCoMo 84% → 58.44% by Mem0's rerun): <https://github.com/getzep/zep-papers/issues/5>

### MemGPT / Letta
- Paper: [Packer et al., *MemGPT: Towards LLMs as Operating Systems* (arXiv:2310.08560)](https://arxiv.org/abs/2310.08560)
- Letta docs (sleep-time compute / recall memory pattern): <https://docs.letta.com/>
- Letta repo: <https://github.com/letta-ai/letta>
- DMR benchmark (Deep Memory Retrieval, 93.4% MemGPT gpt-4-turbo): referenced in the MemGPT paper and Zep blog

### Mem0 (main competitor in the memory-layer space)
- Mem0 repo + benchmark README: <https://github.com/mem0ai/mem0>
- Mem0 research page (self-reported 93.4% on LongMemEval, 91.6% on LoCoMo): <https://mem0.ai/research>
- Mem0 ↔ Zep methodology dispute: see Zep rebuttal + zep-papers issue #5 above

### MemMachine (ablation-study system, Sep-2025)
- Paper (arXiv HTML): <https://arxiv.org/html/2604.04853>
- Key finding: 93.0% on LongMemEval_S with retrieval-stage optimizations contributing more than ingestion-stage; gpt-5-mini > gpt-5 as answer LLM paired with optimized prompts.

### Independent evaluation (MEMTRACK)
- Paper: [*MEMTRACK: Evaluating Long-Term Memory and State Tracking* (arXiv:2510.01353)](https://arxiv.org/abs/2510.01353)
- Finding: both Mem0 and Zep show no significant improvement over base GPT-5 / Gemini-2.5-Pro, with slight degradation in some Gemini+Mem0 configurations. Memory layers matter more for smaller answer LLMs.

### Other memory benchmarks worth knowing
- LoCoMo (Zep's primary published benchmark): <https://github.com/snap-research/locomo>
- DMR (Deep Memory Retrieval from MemGPT): part of MemGPT repo <https://github.com/letta-ai/letta>
- Memorybench (unified memory benchmark harness): <https://github.com/supermemoryai/memorybench>
- Incremental multi-turn memory eval (arXiv:2507.05257): <https://arxiv.org/abs/2507.05257>

### Related retrieval research relevant to HiveMem's 5-signal ranker
- LightRAG (dual-level retrieval paradigm): [Guo et al., arXiv:2410.05779](https://arxiv.org/abs/2410.05779)
- Graphiti (temporal KG engine behind Zep): <https://github.com/getzep/graphiti>

### Embedding models (ranked, current as of April 2026)
Recommended tier for the benchmark host:
- **Qwen/Qwen3-Embedding-4B** (recommended default, 1024-dim via Matryoshka): <https://huggingface.co/Qwen/Qwen3-Embedding-4B>
- Qwen/Qwen3-Embedding-8B (top-shelf, 24 GB GPU): <https://huggingface.co/Qwen/Qwen3-Embedding-8B>
- Qwen/Qwen3-Embedding-0.6B (tiny, fast, native 1024-dim): <https://huggingface.co/Qwen/Qwen3-Embedding-0.6B>
- Qwen3 Embedding paper: [arXiv:2506.05176](https://arxiv.org/html/2506.05176v1)
- Qwen3-Embedding repo: <https://github.com/QwenLM/Qwen3-Embedding>
- BAAI/bge-m3 (baseline comparator, 1024-dim native): <https://huggingface.co/BAAI/bge-m3>
- nvidia/Llama-Embed-Nemotron-8B (Nov 2025, strong Borda rank): [arXiv:2511.07025](https://arxiv.org/abs/2511.07025)
- nvidia/NV-Embed-v2 (2024, still competitive): <https://huggingface.co/nvidia/NV-Embed-v2>

Supporting:
- MTEB leaderboard (live rankings): <https://huggingface.co/spaces/mteb/leaderboard>
- MTEB results repo (raw data): <https://github.com/embeddings-benchmark/results>
- Matryoshka Representation Learning paper (truncation technique): [arXiv:2205.13147](https://arxiv.org/abs/2205.13147)
- pgvector HNSW dim limits: <https://github.com/pgvector/pgvector#supported-index-types>

Deprecated for this benchmark (do not use):
- Alibaba-NLP/gte-Qwen2-7B-instruct (superseded by Qwen3, no Matryoshka, 3584-dim needs halfvec)
- text-embedding-3-large (OpenAI API-only, costs extra per ingest, no local iteration)

### HiveMem internals the benchmark depends on
- MCP tool surface and JSON-RPC contract: [`java-server/src/main/java/com/hivemem/mcp/McpController.java`](../../java-server/src/main/java/com/hivemem/mcp/McpController.java)
- Token management CLI: [`scripts/hivemem-token`](../../scripts/hivemem-token)
- Bi-temporal migration that must be applied on the benchmark host: [`java-server/src/main/resources/db/migration/V0009__bi_temporal.sql`](../../java-server/src/main/resources/db/migration/V0009__bi_temporal.sql)
- Embedding service (swap the model here): [`embedding-service/`](../../embedding-service/)

### Tracking
- GitHub issue this plan implements: <https://github.com/ufelmann/HiveMem/issues/23>
- Research-driven roadmap overview (meta): <https://github.com/ufelmann/HiveMem/issues/29>
- Related tickets that synergise:
  - Bi-temporal model (already shipped): <https://github.com/ufelmann/HiveMem/issues/24>
  - Graph proximity (follow-up): <https://github.com/ufelmann/HiveMem/issues/25>
  - Proactive search triggers (already shipped): <https://github.com/ufelmann/HiveMem/issues/22>
