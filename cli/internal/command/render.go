package command

import (
	"encoding/json"
	"fmt"
	"io"

	"github.com/visterion/hivemem/cli/internal/mcp"
)

// Render writes a tool result. A result flagged isError is rendered as an
// error regardless of the output mode — it arrived as an HTTP 200 and would
// otherwise read as success.
func Render(w io.Writer, res *mcp.ToolResult, asJSON bool) error {
	if asJSON {
		_, err := fmt.Fprintln(w, string(res.Raw))
		return err
	}
	if res.IsError {
		_, err := fmt.Fprintf(w, "Tool failed: %s\n", res.Text)
		return err
	}
	var pretty any
	if err := json.Unmarshal([]byte(res.Text), &pretty); err == nil {
		enc := json.NewEncoder(w)
		enc.SetIndent("", "  ")
		return enc.Encode(pretty)
	}
	_, err := fmt.Fprintln(w, res.Text)
	return err
}
