package config

import (
	"fmt"
	"os"
	"path/filepath"

	"github.com/gofrs/flock"
)

// WriteAtomic writes data to path via a temp file in the same directory
// followed by a rename, so a crash mid-write leaves the previous contents
// intact rather than a truncated file.
func WriteAtomic(path string, data []byte, perm os.FileMode) error {
	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, filepath.Base(path)+".tmp-*")
	if err != nil {
		return fmt.Errorf("create temp file: %w", err)
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName) // no-op after a successful rename

	if err := tmp.Chmod(perm); err != nil {
		tmp.Close()
		return fmt.Errorf("chmod temp file: %w", err)
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return fmt.Errorf("write temp file: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return fmt.Errorf("sync temp file: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("close temp file: %w", err)
	}
	if err := os.Rename(tmpName, path); err != nil {
		return fmt.Errorf("rename into place: %w", err)
	}
	return nil
}

// WithLock runs fn while holding an exclusive advisory lock named
// <DataDir>/<name>.lock. The lock is cross-process: it is what keeps two
// mcp-serve instances from refreshing the same credential concurrently.
func WithLock(name string, fn func() error) error {
	dir, err := DataDir()
	if err != nil {
		return err
	}
	lock := flock.New(filepath.Join(dir, name+".lock"))
	if err := lock.Lock(); err != nil {
		return fmt.Errorf("acquire lock %s: %w", name, err)
	}
	defer lock.Unlock()
	return fn()
}
