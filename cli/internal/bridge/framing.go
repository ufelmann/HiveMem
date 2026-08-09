// Package bridge proxies newline-delimited JSON-RPC on stdio to the HTTP /mcp
// endpoint. It forwards frames without understanding tool schemas, so it keeps
// working for server tools this CLI has never seen.
package bridge

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
)

// MaxFrameBytes bounds a single stdin line. bufio.Scanner's 64 KB default
// would truncate a large add_cell or upload_attachment frame; the id would
// then be unrecoverable, nothing would be written to stdout, and the client
// would wait forever.
const MaxFrameBytes = 8 << 20

// Frame is one parsed stdin line.
type Frame struct {
	Raw []byte
	// ID is the raw JSON token, never a decoded value: Go decodes an untyped
	// number as float64, which mangles anything above 2^53.
	ID       json.RawMessage
	HasID    bool
	Method   string
	IsBatch  bool
	BatchIDs []json.RawMessage
	// ParseErr is set when the line was not usable. The caller logs it and
	// moves on rather than dropping it silently.
	ParseErr error
}

// FrameReader reads newline-delimited frames.
type FrameReader struct{ r *bufio.Reader }

// NewFrameReader wraps r with a buffer large enough for real payloads.
func NewFrameReader(r io.Reader) *FrameReader {
	return &FrameReader{r: bufio.NewReaderSize(r, 64<<10)}
}

// Next returns the next frame. io.EOF ends the stream.
func (f *FrameReader) Next() (*Frame, error) {
	line, err := f.readLine()
	if errors.Is(err, errFrameTooLarge) {
		// Oversize frame: data was drained without accumulating. Return a flagged
		// frame with no usable payload. The id is unrecoverable, so synthesis
		// must generate an error response instead.
		return &Frame{ParseErr: err}, nil
	}
	if err != nil {
		return nil, err
	}
	if len(line) == 0 {
		return &Frame{Raw: line, ParseErr: errors.New("empty line")}, nil
	}
	return parseFrame(line), nil
}

// readLine reads one line up to MaxFrameBytes, growing beyond bufio's initial
// buffer as needed. A line longer than the cap is consumed to its end without
// accumulating, then reported as an error, so the following frames still arrive
// and memory usage stays bounded by MaxFrameBytes.
func (f *FrameReader) readLine() ([]byte, error) {
	var buf []byte
	for {
		chunk, isPrefix, err := f.r.ReadLine()
		if err != nil {
			return nil, err
		}
		buf = append(buf, chunk...)
		if len(buf) > MaxFrameBytes {
			// Drain the rest of this line so the next Next() starts clean,
			// discarding data to keep memory bounded.
			for isPrefix {
				_, isPrefix, err = f.r.ReadLine()
				if err != nil {
					break
				}
			}
			return nil, errFrameTooLarge
		}
		if !isPrefix {
			return buf, nil
		}
	}
}

var errFrameTooLarge = fmt.Errorf(
	"input frame exceeds the %d-byte limit", MaxFrameBytes)

// isRealID reports whether id is a genuine JSON-RPC request id (not null or absent).
// This ensures consistent handling of id:null across single frames and batch members.
func isRealID(id json.RawMessage) bool {
	return len(id) > 0 && string(id) != "null"
}

func parseFrame(line []byte) *Frame {
	fr := &Frame{Raw: line}

	trimmed := skipSpace(line)
	if len(trimmed) > 0 && trimmed[0] == '[' {
		fr.IsBatch = true
		var batch []struct {
			ID json.RawMessage `json:"id"`
		}
		if err := json.Unmarshal(line, &batch); err != nil {
			fr.ParseErr = fmt.Errorf("malformed JSON-RPC batch: %w", err)
			return fr
		}
		for _, m := range batch {
			if isRealID(m.ID) {
				fr.BatchIDs = append(fr.BatchIDs, m.ID)
			}
		}
		return fr
	}

	var msg struct {
		ID     json.RawMessage `json:"id"`
		Method string          `json:"method"`
	}
	if err := json.Unmarshal(line, &msg); err != nil {
		fr.ParseErr = fmt.Errorf("malformed JSON-RPC frame: %w", err)
		return fr
	}
	fr.Method = msg.Method
	if isRealID(msg.ID) {
		fr.ID, fr.HasID = msg.ID, true
	}
	return fr
}

func skipSpace(b []byte) []byte {
	for i, c := range b {
		if c != ' ' && c != '\t' && c != '\r' && c != '\n' {
			return b[i:]
		}
	}
	return nil
}

// SynthesizeError builds a JSON-RPC error frame carrying id. The bridge emits
// one whenever the server's answer is not a usable JSON-RPC frame for this
// request — a raw body on stdout would leave the id unanswered.
func SynthesizeError(id json.RawMessage, code int, message string) []byte {
	if len(id) == 0 {
		id = json.RawMessage("null")
	}
	msg, _ := json.Marshal(message)
	return []byte(fmt.Sprintf(
		`{"jsonrpc":"2.0","id":%s,"error":{"code":%d,"message":%s}}`,
		id, code, msg))
}
