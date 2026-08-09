GO ?= go
LDFLAGS := -s -w

.PHONY: test build build-all lint

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
