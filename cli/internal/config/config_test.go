package config

import "testing"

func TestConfigRoundTrip(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())

	c, err := LoadConfig()
	if err != nil {
		t.Fatalf("LoadConfig on a fresh dir: %v", err)
	}
	c.ServerURL = "https://hivemem.example"
	c.ActiveProfile = "work"
	if err := c.Save(); err != nil {
		t.Fatalf("Save: %v", err)
	}

	again, err := LoadConfig()
	if err != nil {
		t.Fatalf("reload: %v", err)
	}
	if again.ServerURL != "https://hivemem.example" || again.ActiveProfile != "work" {
		t.Fatalf("round trip lost data: %+v", again)
	}
}

func TestCorruptConfigIsFatalAndNamesTheFile(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	t.Setenv("XDG_DATA_HOME", t.TempDir())

	cd, _ := ConfigDir()
	_ = WriteAtomic(configPath(cd), []byte("this is not = toml ["), 0o600)

	_, err := LoadConfig()
	if err == nil {
		t.Fatal("a corrupt config.toml must be a fatal error, unlike the cache")
	}
	if !contains(err.Error(), "config.toml") {
		t.Fatalf("error must name the file, got %q", err)
	}
}

func contains(haystack, needle string) bool {
	return len(haystack) >= len(needle) && (haystack == needle ||
		len(haystack) > 0 && indexOf(haystack, needle) >= 0)
}

func indexOf(h, n string) int {
	for i := 0; i+len(n) <= len(h); i++ {
		if h[i:i+len(n)] == n {
			return i
		}
	}
	return -1
}
