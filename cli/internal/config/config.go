package config

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"

	toml "github.com/pelletier/go-toml/v2"
)

// Config is the durable, rarely written configuration. It never holds a token.
type Config struct {
	ServerURL     string `toml:"server_url"`
	ActiveProfile string `toml:"active_profile"`
}

func configPath(dir string) string { return filepath.Join(dir, "config.toml") }

// LoadConfig reads config.toml. A missing file yields a zero-valued Config; a
// malformed one is a fatal error naming the file, because losing the server URL
// silently would leave every later command pointed nowhere.
func LoadConfig() (*Config, error) {
	dir, err := ConfigDir()
	if err != nil {
		return nil, err
	}
	path := configPath(dir)
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return &Config{}, nil
	}
	if err != nil {
		return nil, fmt.Errorf("read %s: %w", path, err)
	}
	var c Config
	if err := toml.Unmarshal(data, &c); err != nil {
		return nil, fmt.Errorf("%s is malformed: %w", path, err)
	}
	return &c, nil
}

// Save writes config.toml atomically under the config lock.
func (c *Config) Save() error {
	dir, err := ConfigDir()
	if err != nil {
		return err
	}
	data, err := toml.Marshal(c)
	if err != nil {
		return fmt.Errorf("encode config: %w", err)
	}
	return WithLock("config", func() error {
		return WriteAtomic(configPath(dir), data, 0o600)
	})
}
