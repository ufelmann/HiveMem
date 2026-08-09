// Package config owns the two on-disk files the CLI keeps (config.toml and
// cache.json), the directories they live in, and the advisory lock that both
// they and the credential store use.
package config

import (
	"os"
	"path/filepath"
	"runtime"
)

const appDir = "hivemem"

// ConfigDir returns the durable configuration directory, creating it 0700.
func ConfigDir() (string, error) {
	var base string
	if runtime.GOOS == "windows" {
		base = os.Getenv("APPDATA")
	} else if x := os.Getenv("XDG_CONFIG_HOME"); x != "" {
		base = x
	} else {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		base = filepath.Join(home, ".config")
	}
	return ensureDir(filepath.Join(base, appDir))
}

// DataDir returns the data directory used for the encrypted credential files
// and every lock file. It is created 0700 on first use regardless of which
// keystore backend is active — on the keyring backends nothing else would
// create it, and the lock lives there.
func DataDir() (string, error) {
	var base string
	if runtime.GOOS == "windows" {
		base = os.Getenv("LOCALAPPDATA")
	} else if x := os.Getenv("XDG_DATA_HOME"); x != "" {
		base = x
	} else {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		base = filepath.Join(home, ".local", "share")
	}
	return ensureDir(filepath.Join(base, appDir))
}

func ensureDir(dir string) (string, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	return dir, nil
}
