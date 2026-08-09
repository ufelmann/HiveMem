package keystore

import (
	"errors"
	"fmt"
)

// ErrPassphraseRequired signals that the encrypted-file backend was selected
// but no passphrase was available. mcp-serve maps this to exit 3 and never
// prompts: its stdin is the JSON-RPC transport and it usually has no terminal.
var ErrPassphraseRequired = errors.New(
	"the encrypted-file keystore needs a passphrase; set HIVEMEM_PASSPHRASE")

// SelectOptions controls backend choice.
type SelectOptions struct {
	// Passphrase, if set, is used directly for the encrypted-file backend.
	Passphrase []byte
	// PassphrasePrompt is called only when the file backend is selected and
	// Passphrase is empty. Leave nil in non-interactive contexts.
	PassphrasePrompt func() ([]byte, error)
	// ForceBackend pins a backend: "keyring" or "encfile". Empty means auto.
	ForceBackend string
}

type candidate struct {
	store     Store
	available bool
}

// Select picks the platform keyring when it is usable and falls back to the
// encrypted file otherwise.
func Select(opts SelectOptions) (Store, error) {
	keyring, keyringOK := platformKeyring()
	return selectFrom(
		candidate{keyring, keyringOK},
		candidate{nil, true}, // the file backend is constructed lazily below
		opts,
	)
}

func selectFrom(keyring, file candidate, opts SelectOptions) (Store, error) {
	wantFile := opts.ForceBackend == "encfile"
	wantKeyring := opts.ForceBackend == "keyring"

	if !wantFile && (wantKeyring || keyring.available) {
		if keyring.store == nil {
			return nil, errors.New("the keyring backend is unavailable on this platform")
		}
		return keyring.store, nil
	}

	pass := opts.Passphrase
	if len(pass) == 0 {
		if opts.PassphrasePrompt == nil {
			return nil, ErrPassphraseRequired
		}
		p, err := opts.PassphrasePrompt()
		if err != nil {
			return nil, fmt.Errorf("read passphrase: %w", err)
		}
		if len(p) == 0 {
			return nil, ErrPassphraseRequired
		}
		pass = p
	}
	if file.store != nil {
		return file.store, nil
	}
	return NewEncFile(pass), nil
}

// otherBackendHolds reports whether a backend other than the selected one has a
// credential for this profile. A credential written headless to the encrypted
// file and then read from a desktop session that picks the keyring would
// otherwise look like a lost login.
func otherBackendHolds(profile string, selected Store, all []Store) (string, bool) {
	for _, s := range all {
		if s == nil || s.Name() == selected.Name() {
			continue
		}
		if _, err := s.Get(profile); err == nil {
			return s.Name(), true
		}
	}
	return "", false
}

// OtherBackendHolds is the exported form used by the "not logged in" message.
func OtherBackendHolds(profile string, selected Store) (string, bool) {
	keyring, ok := platformKeyring()
	all := []Store{}
	if ok && keyring != nil {
		all = append(all, keyring)
	}
	if dir, err := probeEncFileExists(profile); err == nil && dir {
		all = append(all, &encFileProbe{})
	}
	return otherBackendHolds(profile, selected, all)
}

// encFileProbe answers only the "does a file exist" question, without needing
// the passphrase — a mismatch hint must not require decrypting anything.
type encFileProbe struct{}

func (e *encFileProbe) Name() string                    { return "encrypted file" }
func (e *encFileProbe) Get(string) (*Credential, error) { return &Credential{}, nil }
func (e *encFileProbe) Set(string, *Credential) error   { return errors.New("probe is read-only") }
func (e *encFileProbe) Delete(string) error             { return errors.New("probe is read-only") }
