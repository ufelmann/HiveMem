package keystore

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"golang.org/x/crypto/argon2"

	"github.com/visterion/hivemem/cli/internal/config"
)

const (
	argonTime    = 1
	argonMemory  = 64 * 1024 // 64 MiB
	argonThreads = 4
	argonKeyLen  = 32
	saltLen      = 16
)

// encFile stores each profile in its own AES-256-GCM file, keyed by an
// Argon2id-derived key. One file per profile so that the per-profile advisory
// lock actually covers the whole write.
type encFile struct{ passphrase []byte }

// NewEncFile returns the encrypted-file backend.
func NewEncFile(passphrase []byte) Store { return &encFile{passphrase: passphrase} }

func (e *encFile) Name() string { return "encrypted file" }

func (e *encFile) path(profile string) (string, error) {
	dir, err := config.DataDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "creds-"+profile+".enc"), nil
}

func (e *encFile) Get(profile string) (*Credential, error) {
	path, err := e.path(profile)
	if err != nil {
		return nil, err
	}
	raw, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("read credential file: %w", err)
	}
	if len(raw) < saltLen {
		return nil, errors.New("credential file is truncated")
	}

	salt, body := raw[:saltLen], raw[saltLen:]
	gcm, err := e.aead(salt)
	if err != nil {
		return nil, err
	}
	if len(body) < gcm.NonceSize() {
		return nil, errors.New("credential file is truncated")
	}
	nonce, ct := body[:gcm.NonceSize()], body[gcm.NonceSize():]

	plain, err := gcm.Open(nil, nonce, ct, nil)
	if err != nil {
		// Authentication failure: a wrong passphrase or a tampered file. The
		// two are indistinguishable by design.
		return nil, errors.New("could not decrypt credential file (wrong passphrase?)")
	}
	var c Credential
	if err := json.Unmarshal(plain, &c); err != nil {
		return nil, fmt.Errorf("decode credential: %w", err)
	}
	c.Register()
	return &c, nil
}

func (e *encFile) Set(profile string, c *Credential) error {
	path, err := e.path(profile)
	if err != nil {
		return err
	}
	plain, err := json.Marshal(c)
	if err != nil {
		return fmt.Errorf("encode credential: %w", err)
	}

	salt := make([]byte, saltLen)
	if _, err := io.ReadFull(rand.Reader, salt); err != nil {
		return fmt.Errorf("generate salt: %w", err)
	}
	gcm, err := e.aead(salt)
	if err != nil {
		return err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return fmt.Errorf("generate nonce: %w", err)
	}

	out := append([]byte{}, salt...)
	out = append(out, nonce...)
	out = gcm.Seal(out, nonce, plain, nil)
	return config.WriteAtomic(path, out, 0o600)
}

func (e *encFile) Delete(profile string) error {
	path, err := e.path(profile)
	if err != nil {
		return err
	}
	if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}

func (e *encFile) aead(salt []byte) (cipher.AEAD, error) {
	key := argon2.IDKey(e.passphrase, salt, argonTime, argonMemory, argonThreads, argonKeyLen)
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

// probeEncFileExists reports whether an encrypted credential file exists for
// this profile, without decrypting it.
func probeEncFileExists(profile string) (bool, error) {
	dir, err := config.DataDir()
	if err != nil {
		return false, err
	}
	_, err = os.Stat(filepath.Join(dir, "creds-"+profile+".enc"))
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return true, nil
}
