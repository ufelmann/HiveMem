GO ?= go
LDFLAGS := -s -w

.PHONY: test build build-all lint capture-schemas

test:
	CGO_ENABLED=0 $(GO) test ./... -count=1

build:
	CGO_ENABLED=0 $(GO) build -ldflags '$(LDFLAGS)' -o bin/hivemem .

build-all:
	CGO_ENABLED=0 GOOS=linux   GOARCH=amd64 $(GO) build -ldflags '$(LDFLAGS)' -o bin/hivemem-linux-amd64 .
	CGO_ENABLED=0 GOOS=linux   GOARCH=arm64 $(GO) build -ldflags '$(LDFLAGS)' -o bin/hivemem-linux-arm64 .
	CGO_ENABLED=0 GOOS=windows GOARCH=amd64 $(GO) build -ldflags '$(LDFLAGS)' -o bin/hivemem-windows-amd64.exe .

lint:
	$(GO) vet ./...

# Regenerates the golden tool-schema fixture. The target MUST run against a
# LOCAL dev server (docker compose up + mvnw spring-boot:run) with an admin
# token — NEVER production, and never anything else reachable over the
# network. This repository has already leaked real personal data once
# through a fixture generated from a live system; the host check below fails
# closed on purpose. Tool schemas are code-derived, but descriptions are free
# text and this file lands in a PUBLIC repository: after capturing, open
# captured-tools.json and read it — review the content, do not just diff it.
CAPTURE_URL ?= http://localhost:8080
capture-schemas:
	@echo "$(CAPTURE_URL)" | grep -Eq '^http://(localhost|127\.0\.0\.1)(:[0-9]+)?(/.*)?$$' || \
	  { echo "refusing to capture from $(CAPTURE_URL): use a local dev server"; exit 1; }
	@test -n "$(CAPTURE_TOKEN)" || { echo "set CAPTURE_TOKEN to an admin-role dev token"; exit 1; }
	curl -sS -X POST "$(CAPTURE_URL)/mcp" \
	  -H 'Content-Type: application/json' \
	  -H "Authorization: Bearer $(CAPTURE_TOKEN)" \
	  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \
	  | python3 -c 'import json,sys; d=json.load(sys.stdin); t=d["result"]["tools"]; \
	      print(len(t), "tools captured", file=sys.stderr); \
	      sys.exit(1) if len(t) < 40 else json.dump(t, sys.stdout, indent=2)' \
	  > internal/command/testdata/captured-tools.json
	@echo "Review internal/command/testdata/captured-tools.json for content before committing."
