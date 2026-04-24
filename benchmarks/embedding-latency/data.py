"""Benchmark data shaped like real HiveMem cells.

Instead of the short STS-Benchmark sentences, these samples are
paragraph-length (150–300 words) and mix English and German, which mirrors
how HiveMem summaries, key-points blocks and note content actually look.
All text is synthetic, covers generic software-engineering topics, and
contains no private or deployment-specific information.

This lets the benchmark expose two things the STS-B sentences cannot:

1. Whether the 128-token input cap of a small multilingual MiniLM silently
   truncates paragraph-length memories — longer paragraphs lose their tail.
2. Whether the model handles cross-language similarity (DE ↔ EN), since
   HiveMem content routinely switches languages within a project.

PAIRS lists gold similarity scores on the usual 0–5 STS scale, annotated
with the same reasoning a human annotator would apply (translation of the
same text = 5, same topic different angle ≈ 3–4, loosely related ≈ 1–2,
unrelated ≈ 0).
"""

from __future__ import annotations


PARAGRAPHS: dict[str, str] = {
    "pg_backup_en": (
        "PostgreSQL backups in production usually rely on pg_dump for logical "
        "dumps and pg_basebackup for physical ones. pg_dump reads through a "
        "single transaction, emits SQL or a custom archive, and can be restored "
        "into any compatible version. It becomes slow on very large databases "
        "because schema and data serialize through one connection. pg_basebackup "
        "copies the data directory at the file level while WAL archiving captures "
        "ongoing changes, which enables point-in-time restore. For a service "
        "holding a few hundred megabytes, a nightly gzipped pg_dump written to a "
        "mounted volume is usually enough; multi-terabyte clusters lean on "
        "streaming physical backups with WAL archiving to object storage."
    ),
    "pg_backup_de": (
        "PostgreSQL-Backups in Produktion setzen üblicherweise auf pg_dump für "
        "logische Dumps und pg_basebackup für physische. pg_dump liest in einer "
        "einzigen Transaktion, schreibt SQL oder ein Custom-Archiv und kann in "
        "jede kompatible Version zurückgespielt werden. Bei sehr großen "
        "Datenbanken wird es langsam, weil Schema und Daten seriell durch eine "
        "Verbindung laufen. pg_basebackup kopiert das Data-Directory auf "
        "Dateiebene, während WAL-Archivierung die laufenden Änderungen "
        "aufzeichnet, sodass Point-in-Time-Restore möglich wird. Für einen "
        "Service mit ein paar hundert Megabyte reicht ein nächtlicher "
        "gzip-komprimierter pg_dump auf ein gemountetes Volume; für "
        "Multi-Terabyte-Cluster ist Streaming Physical Backup mit "
        "WAL-Archivierung in Object Storage der Standard."
    ),
    "tdd_en": (
        "Test-driven development flips the order of writing code and writing "
        "tests. You begin with a failing test that states the behaviour you "
        "want, write the smallest amount of production code that makes it "
        "pass, then refactor with the suite as a safety net. The cycle is "
        "deliberately tight, often under a minute per iteration, which forces "
        "the work into verifiable steps. Common objections are that it feels "
        "slow or that tests constrain design. In practice the opposite tends "
        "to hold: tested code is easier to refactor, and the act of writing "
        "the test first produces smaller, composable units because anything "
        "hard to test gets rewritten before it accumulates."
    ),
    "tdd_de": (
        "Testgetriebene Entwicklung dreht die Reihenfolge von Code und Tests "
        "um. Man beginnt mit einem fehlschlagenden Test, der das gewünschte "
        "Verhalten beschreibt, schreibt die kleinste Menge Produktionscode, "
        "die ihn grün macht, und refaktorisiert anschließend mit dem Testnetz "
        "als Absicherung. Der Zyklus ist bewusst kurz, oft unter einer Minute, "
        "was dazu zwingt, die Arbeit in überprüfbare Schritte zu zerlegen. "
        "Übliche Einwände sind, es fühle sich langsam an oder Tests würden das "
        "Design einschränken. In der Praxis ist das Gegenteil typisch: "
        "getesteter Code lässt sich leichter refaktorisieren, und das "
        "Test-first-Vorgehen erzeugt kleinere, komponierbare Einheiten, weil "
        "schwer Testbares umgeschrieben wird, bevor es Schaden anrichtet."
    ),
    "docker_multistage": (
        "Docker multi-stage builds separate the build environment from the "
        "runtime image. A first stage starts from a language toolchain such as "
        "eclipse-temurin:25-jdk, installs build tools and compiles the "
        "project. A second stage starts from a minimal runtime image and "
        "copies only the resulting artifact from the build stage. The pattern "
        "shrinks final image size from gigabytes to hundreds of megabytes, "
        "strips compilers and package managers that attackers would otherwise "
        "find at runtime, and keeps the Dockerfile self-contained so CI does "
        "not need to assemble an image from loose artifacts."
    ),
    "docker_layer_cache": (
        "Docker caches each instruction in a Dockerfile as a layer. When an "
        "instruction's inputs are unchanged, the daemon reuses the cached "
        "result instead of re-running the command. Ordering therefore matters: "
        "place instructions with stable inputs like dependency installation "
        "before instructions with volatile inputs like copying source code. A "
        "COPY package.json followed by npm install followed by COPY . keeps "
        "the expensive dependency step cached across source edits. Breaking "
        "this order forces a full rebuild on every commit and turns a "
        "20-second incremental build into a five-minute one."
    ),
    "embed_minilm": (
        "The paraphrase-multilingual-MiniLM-L12-v2 model is a small sentence "
        "encoder with 384 output dimensions and a 128-token input window. It "
        "is distilled from an XLM-R teacher and trained on paraphrase pairs "
        "across roughly fifty languages. Its strengths are low CPU latency, a "
        "small on-disk footprint near 120 MB in ONNX form, and competitive "
        "performance on short-sentence similarity. The main weakness is the "
        "128-token input cap, which silently truncates longer documents to "
        "about the first hundred words. For paragraph-length memories it "
        "works, but semantic overlap in the truncated tail is lost."
    ),
    "embed_bge_m3": (
        "BGE-M3 is a multilingual embedding model from BAAI with 1024 output "
        "dimensions and an 8192-token input window. It produces dense, sparse "
        "and multi-vector representations in a single forward pass, so the "
        "same model can drive cosine search, BM25-style lexical search and "
        "late-interaction retrieval. Compared to smaller MiniLM-class models, "
        "it trades latency and memory for much better recall on long documents "
        "and cross-lingual queries. Running it without a GPU is viable only "
        "for low query rates, but the quality gain on paragraph-length and "
        "mixed-language content is substantial."
    ),
    "rest_url_version": (
        "URL-based versioning puts the API version directly in the path, as "
        "in /v1/users. Clients see the version in every request, routing is "
        "trivial because a reverse proxy can dispatch per prefix, and tools "
        "like Swagger treat each version as a separate specification. The "
        "cost is that the URL of a resource changes across versions, which "
        "complicates caching, bookmarking and linking between resources. "
        "Breaking changes require coordinated client updates, and maintaining "
        "parallel code paths for /v1 and /v2 grows the surface area of the "
        "service."
    ),
    "rest_header_version": (
        "Header-based versioning keeps URLs stable and negotiates the response "
        "shape through Accept or a custom header like Api-Version. The "
        "resource URL does not change between versions, which preserves "
        "hypermedia links and caching semantics. The downside is that the "
        "version is invisible in tools that only look at URLs, routing "
        "requires a content-negotiation layer, and debugging with curl means "
        "remembering to set the header. Prefer header versioning when the API "
        "is used by long-lived clients and URL versioning when humans or "
        "simple tools are the primary consumers."
    ),
    "oauth_refresh": (
        "OAuth 2.0 refresh tokens let a client obtain a new short-lived "
        "access token without re-prompting the user. When the access token "
        "expires, the client sends the refresh token to the authorization "
        "server's token endpoint and receives a fresh access token, often "
        "rotated alongside a new refresh token. Storing refresh tokens "
        "securely is essential because they carry long-lived authority. A "
        "revoked or leaked refresh token must be blocked at the server "
        "immediately, which is why production deployments track them in a "
        "database with a revoked_at column rather than relying on "
        "self-contained JWTs."
    ),
    "sourdough_en": (
        "Sourdough fermentation depends on a living culture of wild yeast and "
        "lactic acid bacteria. The baker maintains a starter by feeding it "
        "flour and water at regular intervals, which keeps the microbial "
        "population active. A well-kept starter at room temperature needs "
        "feeding every twelve hours; in the refrigerator it can go a week. "
        "The flavour of the final loaf is shaped by how sour the starter is "
        "when it goes into the dough, the hydration of the dough itself, and "
        "the length of the bulk fermentation at a controlled temperature."
    ),
    "k8s_deployment": (
        "A Kubernetes Deployment declares the desired state of a replicated "
        "application: how many pods, which container image, which rollout "
        "strategy. The controller reconciles the live state toward the spec, "
        "creating ReplicaSets to manage pods. Rolling updates happen in "
        "place: a new ReplicaSet scales up while the old one scales down, "
        "with readiness probes gating traffic. Rollbacks are a single kubectl "
        "command because the previous ReplicaSet remains as a revision, so "
        "recovering from a bad release is fast and auditable."
    ),
    "compose_deployment": (
        "Docker Compose describes a multi-container application in a single "
        "YAML file. Each service defines an image, environment, volumes and "
        "network membership, and compose up brings them up in dependency "
        "order. It is well suited to single-host deployments, development "
        "environments and small production services. It lacks the scheduling, "
        "self-healing and rolling-update capabilities of a cluster manager, "
        "which is why teams outgrow it once they need horizontal scaling or "
        "zero-downtime releases across multiple hosts."
    ),
    "git_merge": (
        "A git merge joins two histories by creating a merge commit with both "
        "tips as parents. The original commits remain intact, which preserves "
        "the exact sequence each contributor made. The cost is that the graph "
        "grows bushy with many merge nodes, making history harder to read. "
        "Fast-forward merges avoid the extra commit when one branch is a "
        "direct descendant of the other. Teams that value honest history over "
        "a linear log tend to prefer merge."
    ),
    "git_rebase": (
        "A git rebase rewrites a branch by replaying its commits on top of "
        "another tip. The result is a linear history with no merge commits, "
        "easier to read and bisect, but commit hashes change, so collaborators "
        "tracking the branch must fetch and reset. Interactive rebase lets you "
        "squash, reorder or edit commits before publishing. Teams that value "
        "a clean log over exact provenance prefer rebase for feature branches "
        "and merge only at the integration boundary."
    ),
}


# (paragraph_key_a, paragraph_key_b, gold_score_0_to_5, reasoning)
PAIRS: list[tuple[str, str, float, str]] = [
    ("pg_backup_en", "pg_backup_de", 5.0, "same content, translated EN<->DE"),
    ("tdd_en",       "tdd_de",       5.0, "same content, translated EN<->DE"),
    ("docker_multistage", "docker_layer_cache", 3.5, "same topic: Dockerfile optimisation"),
    ("embed_minilm", "embed_bge_m3", 3.2, "same topic: multilingual sentence encoders"),
    ("rest_url_version", "rest_header_version", 3.5, "same topic: REST API versioning"),
    ("k8s_deployment", "compose_deployment", 2.5, "same topic: deployment orchestration, different scale"),
    ("git_merge",    "git_rebase",    3.5, "same topic: git integration strategy"),
    ("rest_url_version", "oauth_refresh", 1.8, "loosely related: same API/auth ecosystem"),
    ("docker_multistage", "k8s_deployment", 2.0, "loosely related: deployment tooling"),
    ("pg_backup_en", "oauth_refresh", 0.6, "unrelated: storage ops vs auth"),
    ("tdd_en",       "sourdough_en",  0.2, "unrelated: software vs baking"),
    ("embed_minilm", "sourdough_en",  0.1, "unrelated: NLP vs baking"),
]


# Subset used for latency sampling (a realistic mix of short/long, EN/DE).
LATENCY_KEYS: list[str] = [
    "pg_backup_en",
    "pg_backup_de",
    "tdd_en",
    "tdd_de",
    "docker_multistage",
    "embed_minilm",
    "embed_bge_m3",
    "rest_url_version",
    "oauth_refresh",
    "k8s_deployment",
]
