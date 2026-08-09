package config

import (
	"os"
	"path/filepath"
	"sync"
	"testing"
)

func TestWriteAtomicLeavesNoPartialFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.toml")

	if err := WriteAtomic(path, []byte("first"), 0o600); err != nil {
		t.Fatalf("first write: %v", err)
	}
	if err := WriteAtomic(path, []byte("second"), 0o600); err != nil {
		t.Fatalf("second write: %v", err)
	}

	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if string(got) != "second" {
		t.Fatalf("got %q, want %q", got, "second")
	}

	// No temp files may survive a successful write.
	entries, _ := os.ReadDir(dir)
	if len(entries) != 1 {
		t.Fatalf("expected exactly one file, found %d", len(entries))
	}
}

func TestWriteAtomicSetsMode(t *testing.T) {
	if os.Getenv("GOOS") == "windows" {
		t.Skip("POSIX modes are not meaningful on Windows")
	}
	path := filepath.Join(t.TempDir(), "creds.enc")
	if err := WriteAtomic(path, []byte("x"), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Fatalf("mode = %o, want 600", perm)
	}
}

func TestWithLockSerializesWriters(t *testing.T) {
	t.Setenv("XDG_DATA_HOME", t.TempDir())

	var mu sync.Mutex
	var order []int
	inside := 0
	maxInside := 0

	var wg sync.WaitGroup
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func(n int) {
			defer wg.Done()
			_ = WithLock("test-profile", func() error {
				mu.Lock()
				inside++
				if inside > maxInside {
					maxInside = inside
				}
				order = append(order, n)
				mu.Unlock()

				mu.Lock()
				inside--
				mu.Unlock()
				return nil
			})
		}(i)
	}
	wg.Wait()

	if maxInside != 1 {
		t.Fatalf("%d goroutines were inside the lock at once, want 1", maxInside)
	}
	if len(order) != 4 {
		t.Fatalf("expected 4 critical sections, got %d", len(order))
	}
}

func TestDataDirIsCreatedWith0700(t *testing.T) {
	if os.Getenv("GOOS") == "windows" {
		t.Skip("POSIX modes are not meaningful on Windows")
	}
	base := t.TempDir()
	t.Setenv("XDG_DATA_HOME", base)

	dir, err := DataDir()
	if err != nil {
		t.Fatalf("DataDir: %v", err)
	}
	info, err := os.Stat(dir)
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	if perm := info.Mode().Perm(); perm != 0o700 {
		t.Fatalf("mode = %o, want 700", perm)
	}
}
