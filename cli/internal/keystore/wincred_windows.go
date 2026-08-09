//go:build windows

package keystore

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"syscall"
	"unsafe"

	"github.com/danieljoos/wincred"

	"github.com/visterion/hivemem/cli/internal/config"
)

// credentialBlobLimit is the documented CredentialBlob ceiling. A long access
// token plus a refresh token can approach it, so the DPAPI file fallback is a
// real path, not a theoretical one.
const credentialBlobLimit = 2560

type winCred struct{}

func (w *winCred) Name() string { return "Windows Credential Manager" }

// platformKeyring returns the Credential Manager backend, which is always
// present on Windows.
func platformKeyring() (Store, bool) { return &winCred{}, true }

func targetName(profile string) string { return "hivemem/" + profile }

func (w *winCred) Get(profile string) (*Credential, error) {
	blob, err := readGeneric(profile)
	if err == nil {
		var c Credential
		if err := json.Unmarshal(blob, &c); err != nil {
			return nil, fmt.Errorf("decode credential: %w", err)
		}
		c.Register()
		return &c, nil
	}
	if !errors.Is(err, ErrNotFound) {
		return nil, err
	}
	// Fall back to the DPAPI-protected file.
	return w.getFromFile(profile)
}

func readGeneric(profile string) ([]byte, error) {
	cred, err := wincred.GetGenericCredential(targetName(profile))
	if err != nil {
		return nil, ErrNotFound
	}
	return cred.CredentialBlob, nil
}

func (w *winCred) Set(profile string, c *Credential) error {
	blob, err := json.Marshal(c)
	if err != nil {
		return err
	}
	if len(blob) <= credentialBlobLimit {
		cred := wincred.NewGenericCredential(targetName(profile))
		cred.CredentialBlob = blob
		// Per-user, and deliberately not roamed to a domain profile server.
		cred.Persist = wincred.PersistLocalMachine
		if err := cred.Write(); err == nil {
			return nil
		}
	}
	return w.setToFile(profile, blob)
}

func (w *winCred) Delete(profile string) error {
	if cred, err := wincred.GetGenericCredential(targetName(profile)); err == nil {
		_ = cred.Delete()
	}
	path, err := w.filePath(profile)
	if err != nil {
		return err
	}
	if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}

func (w *winCred) filePath(profile string) (string, error) {
	dir, err := config.DataDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "creds-"+profile+".dpapi"), nil
}

func (w *winCred) setToFile(profile string, blob []byte) error {
	enc, err := dpapiProtect(blob)
	if err != nil {
		return fmt.Errorf("DPAPI protect: %w", err)
	}
	path, err := w.filePath(profile)
	if err != nil {
		return err
	}
	return config.WriteAtomic(path, enc, 0o600)
}

func (w *winCred) getFromFile(profile string) (*Credential, error) {
	path, err := w.filePath(profile)
	if err != nil {
		return nil, err
	}
	enc, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	blob, err := dpapiUnprotect(enc)
	if err != nil {
		return nil, fmt.Errorf("DPAPI unprotect: %w", err)
	}
	var c Credential
	if err := json.Unmarshal(blob, &c); err != nil {
		return nil, fmt.Errorf("decode credential: %w", err)
	}
	c.Register()
	return &c, nil
}

// --- DPAPI, user scope ---

type dataBlob struct {
	cbData uint32
	pbData *byte
}

var (
	crypt32            = syscall.NewLazyDLL("crypt32.dll")
	procCryptProtect   = crypt32.NewProc("CryptProtectData")
	procCryptUnprotect = crypt32.NewProc("CryptUnprotectData")
)

func newBlob(b []byte) *dataBlob {
	if len(b) == 0 {
		return &dataBlob{}
	}
	return &dataBlob{cbData: uint32(len(b)), pbData: &b[0]}
}

func (b *dataBlob) bytes() []byte {
	out := make([]byte, b.cbData)
	copy(out, unsafe.Slice(b.pbData, b.cbData))
	return out
}

func dpapiProtect(in []byte) ([]byte, error) {
	var out dataBlob
	r, _, err := procCryptProtect.Call(
		uintptr(unsafe.Pointer(newBlob(in))), 0, 0, 0, 0, 0,
		uintptr(unsafe.Pointer(&out)))
	if r == 0 {
		return nil, err
	}
	defer syscall.LocalFree(syscall.Handle(unsafe.Pointer(out.pbData)))
	return out.bytes(), nil
}

func dpapiUnprotect(in []byte) ([]byte, error) {
	var out dataBlob
	r, _, err := procCryptUnprotect.Call(
		uintptr(unsafe.Pointer(newBlob(in))), 0, 0, 0, 0, 0,
		uintptr(unsafe.Pointer(&out)))
	if r == 0 {
		return nil, err
	}
	defer syscall.LocalFree(syscall.Handle(unsafe.Pointer(out.pbData)))
	return out.bytes(), nil
}
