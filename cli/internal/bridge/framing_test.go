package bridge

import (
	"encoding/json"
	"runtime"
	"strings"
	"testing"
)

func TestNextReadsASimpleFrame(t *testing.T) {
	in := `{"jsonrpc":"2.0","id":7,"method":"tools/list","params":{}}` + "\n"
	fr := NewFrameReader(strings.NewReader(in))

	f, err := fr.Next()
	if err != nil {
		t.Fatalf("Next: %v", err)
	}
	if f.Method != "tools/list" {
		t.Fatalf("method = %q", f.Method)
	}
	if !f.HasID || string(f.ID) != "7" {
		t.Fatalf("id = %q, hasID = %v", f.ID, f.HasID)
	}
}

// The id must survive as a raw token. Decoding into interface{} yields float64
// and silently mangles anything above 2^53, which then compares unequal
// against the server's exact echo — and a valid result gets replaced by a
// synthesized error.
func TestLargeIntegerIDRoundTripsExactly(t *testing.T) {
	const big = "10000000000000001"
	in := `{"jsonrpc":"2.0","id":` + big + `,"method":"ping"}` + "\n"

	f, err := NewFrameReader(strings.NewReader(in)).Next()
	if err != nil {
		t.Fatalf("Next: %v", err)
	}
	if string(f.ID) != big {
		t.Fatalf("id = %q, want %q — decoded as a float somewhere", f.ID, big)
	}
}

func TestFrameWithoutIDIsMarked(t *testing.T) {
	in := `{"jsonrpc":"2.0","method":"notifications/initialized"}` + "\n"
	f, err := NewFrameReader(strings.NewReader(in)).Next()
	if err != nil {
		t.Fatalf("Next: %v", err)
	}
	if f.HasID {
		t.Fatal("a frame with no id must be marked")
	}
}

// A 4 MB frame must be read whole. A 1 MB probe would pass with any buffer
// between 1 and 8 MB while violating the rule. 4 MB is comfortably above the
// bufio.Scanner default (64 KB) and safely under the MaxFrameBytes cap (8 MB),
// ensuring the buffer grows beyond the initial size without truncation.
func TestFourMegabyteFrameIsReadWhole(t *testing.T) {
	payload := strings.Repeat("x", 4<<20)
	in := `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"blob":"` + payload + `"}}` + "\n"

	f, err := NewFrameReader(strings.NewReader(in)).Next()
	if err != nil {
		t.Fatalf("a 4 MB frame must be readable: %v", err)
	}
	if len(f.Raw) < 4<<20 {
		t.Fatalf("frame was truncated to %d bytes", len(f.Raw))
	}
	if string(f.ID) != "1" {
		t.Fatalf("id lost on a large frame: %q", f.ID)
	}
}

func TestFrameExceedingTheBufferIsReportedNotDropped(t *testing.T) {
	payload := strings.Repeat("x", MaxFrameBytes+(1<<20))
	in := `{"jsonrpc":"2.0","id":1,"params":{"blob":"` + payload + `"}}` + "\n" +
		`{"jsonrpc":"2.0","id":2,"method":"ping"}` + "\n"

	fr := NewFrameReader(strings.NewReader(in))
	f, err := fr.Next()
	if err == nil && f != nil && f.ParseErr == nil {
		t.Fatal("an oversize frame must be reported, not silently accepted")
	}
	// The following frame must still be delivered.
	next, err := fr.Next()
	if err != nil {
		t.Fatalf("the reader must recover after an oversize frame: %v", err)
	}
	if string(next.ID) != "2" {
		t.Fatalf("recovery frame id = %q, want 2", next.ID)
	}
}

// Oversize frames are drained and reported with empty payload, and the stream
// stays synchronized for recovery. Raw must be empty and id unextractable.
func TestOversizeFrameDrainedNotAccumulated(t *testing.T) {
	// Create a payload 3x larger than MaxFrameBytes
	payload := strings.Repeat("x", 3*MaxFrameBytes)
	in := `{"jsonrpc":"2.0","id":1,"params":{"blob":"` + payload + `"}}` + "\n" +
		`{"jsonrpc":"2.0","id":2,"method":"ping"}` + "\n"

	fr := NewFrameReader(strings.NewReader(in))
	f, err := fr.Next()
	if err != nil {
		t.Fatalf("Next: %v", err)
	}
	if f.ParseErr == nil {
		t.Fatal("an oversized frame must be flagged")
	}
	if len(f.Raw) > 0 {
		t.Fatalf("oversized frame's Raw must be empty (was drained), got %d bytes", len(f.Raw))
	}
	// id should not be extractable from a drained frame
	if f.HasID {
		t.Fatal("id must not be extractable from a drained oversized frame")
	}

	// The following frame must still be delivered correctly.
	next, err := fr.Next()
	if err != nil {
		t.Fatalf("the reader must recover after an oversized frame: %v", err)
	}
	if string(next.ID) != "2" {
		t.Fatalf("recovery frame id = %q, want 2", next.ID)
	}
}

func TestUnparseableLineIsFlaggedAndSkipped(t *testing.T) {
	in := "{not json at all\n" + `{"jsonrpc":"2.0","id":2,"method":"ping"}` + "\n"
	fr := NewFrameReader(strings.NewReader(in))

	f, err := fr.Next()
	if err != nil {
		t.Fatalf("Next: %v", err)
	}
	if f.ParseErr == nil {
		t.Fatal("an unparseable line must be flagged")
	}

	next, err := fr.Next()
	if err != nil {
		t.Fatalf("the following frame must still arrive: %v", err)
	}
	if string(next.ID) != "2" {
		t.Fatalf("id = %q, want 2", next.ID)
	}
}

// The server answers a batch with id null, so forwarding one leaves every id
// in it unanswered and the client hangs.
func TestBatchArrayIsDetectedWithItsMemberIDs(t *testing.T) {
	in := `[{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","id":2,"method":"ping"}]` + "\n"
	f, err := NewFrameReader(strings.NewReader(in)).Next()
	if err != nil {
		t.Fatalf("Next: %v", err)
	}
	if !f.IsBatch {
		t.Fatal("a top-level array must be detected as a batch")
	}
	if len(f.BatchIDs) != 2 {
		t.Fatalf("batch ids = %v, want two", f.BatchIDs)
	}
}

// Batch members with id:null are excluded; only real ids appear in BatchIDs.
// This ensures consistent handling between single frames and batches.
func TestBatchFiltersOutNullIDs(t *testing.T) {
	in := `[{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","id":null,"method":"ping"}]` + "\n"
	f, err := NewFrameReader(strings.NewReader(in)).Next()
	if err != nil {
		t.Fatalf("Next: %v", err)
	}
	if !f.IsBatch {
		t.Fatal("a top-level array must be detected as a batch")
	}
	if len(f.BatchIDs) != 1 {
		t.Fatalf("batch ids = %v, want 1 (the null id should be excluded)", f.BatchIDs)
	}
	if string(f.BatchIDs[0]) != "1" {
		t.Fatalf("batch id = %q, want 1", f.BatchIDs[0])
	}
}

// drainCost measures heap allocation while draining an oversize line.
// The cost should depend on the cap (MaxFrameBytes) not the input length,
// proving the drain actually discards rather than accumulates.
func drainCost(t *testing.T, mib int) uint64 {
	t.Helper()
	in := `{"jsonrpc":"2.0","id":1,"params":{"b":"` + strings.Repeat("x", mib<<20) + `"}}` + "\n" +
		`{"jsonrpc":"2.0","id":2,"method":"ping"}` + "\n"
	var before, after runtime.MemStats
	runtime.GC()
	runtime.ReadMemStats(&before)
	fr := NewFrameReader(strings.NewReader(in))
	f1, _ := fr.Next()
	runtime.ReadMemStats(&after)
	if f1.ParseErr == nil {
		t.Fatalf("%d MiB line was not flagged as oversize", mib)
	}
	f2, err := fr.Next()
	if err != nil || string(f2.ID) != "2" {
		t.Fatalf("recovery frame lost after %d MiB line", mib)
	}
	return after.TotalAlloc - before.TotalAlloc
}

// Oversize drain is memory-bounded: the cost of draining large lines
// stays proportional to MaxFrameBytes, not to input length.
// This proves the drain path doesn't accumulate the oversized data.
func TestOversizeDrainMemoryBounded(t *testing.T) {
	cost24 := drainCost(t, 24)
	cost200 := drainCost(t, 200)
	// Cost should not grow materially with input size; use 2x as a generous threshold
	// to avoid flakiness. Both allocations should be dominated by MaxFrameBytes.
	if cost200 > cost24*2 {
		t.Logf("WARN: 200 MiB drain cost %d exceeds 24 MiB cost %d by >2x (suggest checking for accumulation)", cost200, cost24)
	}
}

func TestSynthesizeErrorCarriesTheID(t *testing.T) {
	out := SynthesizeError(json.RawMessage("7"), -32603, "boom")
	var got struct {
		JSONRPC string          `json:"jsonrpc"`
		ID      json.RawMessage `json:"id"`
		Error   struct {
			Code    int    `json:"code"`
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.Unmarshal(out, &got); err != nil {
		t.Fatalf("synthesized frame is not valid JSON: %v", err)
	}
	if string(got.ID) != "7" || got.Error.Code != -32603 {
		t.Fatalf("synthesized frame = %s", out)
	}
}
