// Command hivemem is the HiveMem command-line client.
package main

import (
	"os"

	"github.com/visterion/hivemem/cli/internal/command"
)

func main() { os.Exit(command.Execute()) }
