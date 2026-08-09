//go:build linux

package keystore

import (
	"encoding/json"
	"fmt"
	"os"

	"github.com/godbus/dbus/v5"
)

const (
	ssService    = "org.freedesktop.secrets"
	ssPath       = "/org/freedesktop/secrets"
	ssCollection = "/org/freedesktop/secrets/aliases/default"
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
	if err := obj.Call("org.freedesktop.DBus.Peer.Ping", 0).Err; err != nil {
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
	if err := obj.Call(ssService+".SearchItems", 0, s.attributes(profile)).
		Store(&unlocked, &locked); err != nil {
		return nil, fmt.Errorf("search keyring: %w", err)
	}
	items := append(unlocked, locked...)
	if len(items) == 0 {
		return nil, ErrNotFound
	}

	var session dbus.ObjectPath
	var output dbus.Variant
	if err := obj.Call(ssService+".OpenSession", 0, "plain", dbus.MakeVariant("")).
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
	if err := item.Call(ssService+".Item.GetSecret", 0, session).Store(&secret); err != nil {
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
	if err := obj.Call(ssService+".OpenSession", 0, "plain", dbus.MakeVariant("")).
		Store(&output, &session); err != nil {
		return fmt.Errorf("open keyring session: %w", err)
	}

	props := map[string]dbus.Variant{
		ssService + ".Item.Label":      dbus.MakeVariant("hivemem/" + profile),
		ssService + ".Item.Attributes": dbus.MakeVariant(s.attributes(profile)),
	}
	secret := struct {
		Session     dbus.ObjectPath
		Parameters  []byte
		Value       []byte
		ContentType string
	}{session, nil, blob, "application/json"}

	coll := s.conn.Object(ssService, dbus.ObjectPath(ssCollection))
	var item, prompt dbus.ObjectPath
	if err := coll.Call(ssService+".Collection.CreateItem", 0, props, secret, true).
		Store(&item, &prompt); err != nil {
		return fmt.Errorf("write keyring item: %w", err)
	}
	return nil
}

func (s *secretService) Delete(profile string) error {
	obj := s.conn.Object(ssService, dbus.ObjectPath(ssPath))
	var unlocked, locked []dbus.ObjectPath
	if err := obj.Call(ssService+".SearchItems", 0, s.attributes(profile)).
		Store(&unlocked, &locked); err != nil {
		return fmt.Errorf("search keyring: %w", err)
	}
	items := append(unlocked, locked...)
	if len(items) == 0 {
		return nil
	}
	var prompt dbus.ObjectPath
	if err := s.conn.Object(ssService, items[0]).
		Call(ssService+".Item.Delete", 0).Store(&prompt); err != nil {
		return fmt.Errorf("delete keyring item: %w", err)
	}
	return nil
}
