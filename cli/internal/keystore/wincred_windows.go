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
		if errors.Is(err, wincred.ErrElementNotFound) {
			return nil, ErrNotFound
		}
		// A permissions problem or an unavailable credential service must
		// surface to the caller, not be mistaken for "try the file instead."
		return nil, fmt.Errorf("read keyring credential: %w", err)
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
			// The generic entry is now authoritative; a DPAPI file left over
			// from a previous oversize write would otherwise shadow it on a
			// future Get if the generic entry is ever removed by hand.
			w.removeStaleFile(profile)
			return nil
		}
	}
	if err := w.setToFile(profile, blob); err != nil {
		return err
	}
	// The file is now authoritative; a stale generic entry from a previous
	// undersize write must not keep answering Get with an outdated blob.
	w.removeStaleGeneric(profile)
	return nil
}

// removeStaleFile best-effort removes a leftover DPAPI file once the generic
// Credential Manager entry has become authoritative again. A failure here
// must not fail Set (the write it is cleaning up after already succeeded),
// but it is surfaced on stderr rather than swallowed, since a silent failure
// would leave the exact shadowing this cleanup exists to prevent.
func (w *winCred) removeStaleFile(profile string) {
	path, err := w.filePath(profile)
	if err != nil {
		return
	}
	if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
		fmt.Fprintf(os.Stderr,
			"warning: could not remove stale DPAPI credential file for profile %q: %v\n", profile, err)
	}
}

// removeStaleGeneric best-effort removes a leftover Credential Manager entry
// once the DPAPI file has become authoritative. See removeStaleFile for why
// this warns instead of staying silent, and why it never fails Set.
func (w *winCred) removeStaleGeneric(profile string) {
	cred, err := wincred.GetGenericCredential(targetName(profile))
	if err != nil {
		return // nothing to remove, including the genuine not-found case
	}
	if err := cred.Delete(); err != nil {
		fmt.Fprintf(os.Stderr,
			"warning: could not remove stale Credential Manager entry for profile %q: %v\n", profile, err)
	}
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
