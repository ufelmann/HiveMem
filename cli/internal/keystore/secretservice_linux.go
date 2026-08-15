//go:build linux

package keystore

import (
	"encoding/json"
	"fmt"
	"os"

	"github.com/godbus/dbus/v5"
)

const (
	// ssService is the well-known D-Bus bus name of the Secret Service.
	ssService = "org.freedesktop.secrets"
	ssPath    = "/org/freedesktop/secrets"
	// ssCollection is the default collection alias most keyrings expose.
	ssCollection = "/org/freedesktop/secrets/aliases/default"

	// The Secret Service spec exposes distinct interfaces per object type —
	// none of them named after the bus name above. Calling ssService+".Foo"
	// as an interface name (the previous bug here) always fails with
	// "No such interface" once the daemon actually implements the spec.
	ssServiceIface    = "org.freedesktop.Secret.Service"
	ssCollectionIface = "org.freedesktop.Secret.Collection"
	ssItemIface       = "org.freedesktop.Secret.Item"
)

type secretService struct{ conn *dbus.Conn }

func (s *secretService) Name() string { return "Secret Service (keyring)" }

// platformKeyring returns the Secret Service backend when a session bus with a
// secrets service is reachable.
func platformKeyring() (Store, bool) {
	if os.Getenv("DBUS_SESSION_BUS_ADDRESS") == "" {
		return nil, false
	}
	conn, err := dbus.SessionBus()
	if err != nil {
		return nil, false
	}
	obj := conn.Object(ssService, dbus.ObjectPath(ssPath))
	// A Peer.Ping only proves the bus answered, not that a secrets provider
	// is registered behind it — a bare D-Bus session with no keyring daemon
	// answers Ping just fine. Call a real Secret Service method instead; an
	// empty attribute search is cheap and requires no session or unlock.
	var unlocked, locked []dbus.ObjectPath
	if err := obj.Call(ssServiceIface+".SearchItems", 0, map[string]string{}).
		Store(&unlocked, &locked); err != nil {
		return nil, false
	}
	return &secretService{conn: conn}, true
}

func (s *secretService) attributes(profile string) map[string]string {
	return map[string]string{"service": "hivemem", "profile": profile}
}

func (s *secretService) Get(profile string) (*Credential, error) {
	obj := s.conn.Object(ssService, dbus.ObjectPath(ssPath))
	var unlocked, locked []dbus.ObjectPath
	if err := obj.Call(ssServiceIface+".SearchItems", 0, s.attributes(profile)).
		Store(&unlocked, &locked); err != nil {
		return nil, fmt.Errorf("search keyring: %w", err)
	}
	items := append(unlocked, locked...)
	if len(items) == 0 {
		return nil, ErrNotFound
	}

	var session dbus.ObjectPath
	var output dbus.Variant
	if err := obj.Call(ssServiceIface+".OpenSession", 0, "plain", dbus.MakeVariant("")).
		Store(&output, &session); err != nil {
		return nil, fmt.Errorf("open keyring session: %w", err)
	}

	item := s.conn.Object(ssService, items[0])
	var secret struct {
		Session     dbus.ObjectPath
		Parameters  []byte
		Value       []byte
		ContentType string
	}
	if err := item.Call(ssItemIface+".GetSecret", 0, session).Store(&secret); err != nil {
		return nil, fmt.Errorf("read keyring secret: %w", err)
	}

	var c Credential
	if err := json.Unmarshal(secret.Value, &c); err != nil {
		return nil, fmt.Errorf("decode credential: %w", err)
	}
	c.Register()
	return &c, nil
}

func (s *secretService) Set(profile string, c *Credential) error {
	blob, err := json.Marshal(c)
	if err != nil {
		return err
	}
	obj := s.conn.Object(ssService, dbus.ObjectPath(ssPath))

	var session dbus.ObjectPath
	var output dbus.Variant
	if err := obj.Call(ssServiceIface+".OpenSession", 0, "plain", dbus.MakeVariant("")).
		Store(&output, &session); err != nil {
		return fmt.Errorf("open keyring session: %w", err)
	}

	props := map[string]dbus.Variant{
		ssItemIface + ".Label":      dbus.MakeVariant("hivemem/" + profile),
		ssItemIface + ".Attributes": dbus.MakeVariant(s.attributes(profile)),
	}
	secret := struct {
		Session     dbus.ObjectPath
		Parameters  []byte
		Value       []byte
		ContentType string
	}{session, nil, blob, "application/json"}

	coll := s.conn.Object(ssService, dbus.ObjectPath(ssCollection))
	var item, prompt dbus.ObjectPath
	if err := coll.Call(ssCollectionIface+".CreateItem", 0, props, secret, true).
		Store(&item, &prompt); err != nil {
		return fmt.Errorf("write keyring item: %w", err)
	}
	return nil
}

func (s *secretService) Delete(profile string) error {
	obj := s.conn.Object(ssService, dbus.ObjectPath(ssPath))
	var unlocked, locked []dbus.ObjectPath
	if err := obj.Call(ssServiceIface+".SearchItems", 0, s.attributes(profile)).
		Store(&unlocked, &locked); err != nil {
		return fmt.Errorf("search keyring: %w", err)
	}
	items := append(unlocked, locked...)
	if len(items) == 0 {
		return nil
	}
	var prompt dbus.ObjectPath
	if err := s.conn.Object(ssService, items[0]).
		Call(ssItemIface+".Delete", 0).Store(&prompt); err != nil {
		return fmt.Errorf("delete keyring item: %w", err)
	}
	return nil
}
