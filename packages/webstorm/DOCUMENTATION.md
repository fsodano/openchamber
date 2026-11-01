# WebStorm plugin

## Ownership

This package owns the JetBrains host for OpenChamber:

- the `OpenChamber` tool window and its JCEF browser;
- the managed OpenChamber server process and its health checks;
- the plugin settings and project startup behavior;
- registration of WebStorm's MCP server in OpenCode through OpenChamber's config
  API.

It does not own the web UI, the OpenChamber server, or OpenCode's config format.
Those live in `packages/web`, `packages/ui`, and OpenCode respectively.

The package has no `package.json` on purpose. Bun workspaces match
`packages/*`, and a package without a manifest is ignored, which keeps the
Kotlin build out of `bun install`.

## Invariants

- A managed server binds `127.0.0.1` and runs with `serve --foreground`. The
  project service stops the process when the project closes.
- The repository CLI (`packages/web/bin/cli.js`) runs only when the project is
  trusted and `packages/web/package.json` names `@openchamber/web`. Other
  projects fall back to an installed launcher.
- The plugin attaches to a healthy OpenChamber already listening on the
  configured port instead of starting a second server. It never stops a server
  it did not start.
- The managed server does not inherit OpenChamber desktop variables. Keep
  `OPENCHAMBER_UI_PASSWORD`, `OPENCHAMBER_OPENCODE_PATH`, and
  `OPENCHAMBER_OPENCODE_BIN`; remove the rest of `OPENCHAMBER_*` plus
  `OPENCODE_CONFIG`, `OPENCODE_CONFIG_CONTENT`, `OPENCODE_PASSWORD`, and
  `OPENCODE_SERVER_PASSWORD`.
- Local HTTP calls use HTTP/1.1. Loopback bypasses the IDE proxy; external
  server URLs use the IDE or system proxy.
- The MCP entry is named `webstorm`, is remote, and sets `oauth: false` and an
  explicit protocol (`legacy` or `auto`). It is created with
  `POST /api/config/mcp/webstorm` and updated with `PATCH`. The plugin does not
  write OpenCode config files.
- When `OPENCHAMBER_UI_PASSWORD` is set, config requests log in at
  `/auth/session` and carry the client token. When `IJ_MCP_AUTH_TOKEN` is set,
  the MCP entry forwards it as a bearer header.
- While the server is running, the plugin probes `/health` every five seconds
  and reports failure after three consecutive misses.
- Project disposal does not wait for the server process to exit; it signals the
  process and finishes the wait on the application executor.
- A JCEF browser is created on the EDT and disposed with the tool window
  content.
- The plugin never sets a UI password. An existing `OPENCHAMBER_UI_PASSWORD` is
  passed through.

## Decisions

### Why host the web UI instead of porting the VS Code bridge

The VS Code extension builds its own React surface and routes bridge calls
through the extension host. Reimplementing that bridge in Kotlin would mean
reimplementing terminal, git, file, settings, permission, and source-control
handlers. Running the existing OpenChamber server and loading its UI in JCEF
uses the full web capability set. The trade-off is that the plugin gets the web
surface, not VS Code-specific editor panels.

### Why register MCP through OpenChamber's config API

OpenChamber already reads and writes OpenCode 2 config under `mcp.servers`,
including migration from the v1 shape. Calling its API keeps the plugin out of
the config format and lets OpenCode reload the change itself. Writing
`opencode.json` directly would duplicate the merge rules and could drop
comments or sibling entries.

### Why HTTP/1.1 and a loopback proxy bypass

Java's `HttpClient` defaults to HTTP/2 for `http://` URLs and sends an h2c
upgrade request. The OpenChamber Node server does not answer that upgrade, so
requests time out. The IDE's proxy selector can also send loopback traffic to a
proxy. The clients force HTTP/1.1 and install a selector that returns
`NO_PROXY` for loopback and delegates other hosts to the IDE or system proxy.
The delegation keeps the external server URL setting usable behind a corporate
proxy.

### Why sanitize the spawned environment

WebStorm can be launched from the OpenChamber desktop app, which exports
`OPENCHAMBER_DIST_DIR`, `OPENCHAMBER_RUNTIME=desktop`, `OPENCODE_CONFIG`, and a
managed OpenCode password. Inheriting them makes the tool window serve the
desktop app's assets and share its OpenCode instance. The managed server starts
with a clean OpenChamber environment instead.

### Why the repository CLI needs project trust

`packages/web/bin/cli.js` is executable code from the open project. Running it
on tool window open without consent would let any repository with that path run
code with the user's privileges. The plugin asks the IDE whether the project is
trusted and also checks that the web package identifies itself as
`@openchamber/web`. A configured server command is the user's explicit choice
and runs regardless of project trust.

### Why MCP registration authenticates

A managed server keeps an inherited `OPENCHAMBER_UI_PASSWORD`, so its `/api`
routes require a session even on loopback. The plugin logs in at
`/auth/session`, keeps the session cookie in a `CookieManager`, and sends the
returned client token as a bearer header. Without that, every MCP registration
would return 401 in password-protected mode.

### Why the MCP protocol is written on every update

OpenChamber's PATCH merges into the existing entry. Sending `protocol` only for
the `stream` transport would leave `auto` behind after switching to SSE, and
OpenCode would negotiate a streamable protocol against `/sse`. Every write
therefore pins `legacy` or `auto` to the chosen transport.

### Why the running server is monitored

A second project can attach to a server another project started. When that
other project closes, the attached panel would otherwise keep showing a live
server. A five-second `/health` probe detects the stop and offers a restart.
The same probe catches a crashed or externally stopped server.

### Why the MCP transport defaults to SSE

WebStorm exposes SSE at `/sse` and streamable HTTP at `/stream`. SSE works with
OpenCode's legacy MCP protocol without extra negotiation. Selecting `stream`
also sets `protocol: "auto"` so OpenCode does not try the legacy transport
against a streamable endpoint.

### Why the build targets WebStorm 2026.2

The plugin is built against the installed IDE through `local(...)`, which keeps
the API surface and the runtime identical. `since-build` and `until-build` are
limited to the 262 branch. Supporting older branches needs a platform download
plus a verifier run against each branch.

### Why configured commands run through a login shell

The IDE process does not always see the PATH a terminal sees, especially for
nvm, Homebrew, and Bun installations. A configured command runs through
`/bin/zsh -lc` on macOS, `$SHELL -lc` or `/bin/sh -lc` on Linux, and
`cmd.exe /d /s /c` on Windows. Auto-detected commands use an absolute
executable and avoid the shell.
