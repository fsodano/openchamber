# OpenChamber for WebStorm

WebStorm plugin that puts OpenChamber in a tool window and connects OpenCode to
WebStorm's built-in MCP server.

## What it gives you

- The OpenChamber web UI in a right-side tool window, hosted by JCEF.
- A managed OpenChamber server on `127.0.0.1`, started from the repository CLI
  in a trusted OpenChamber project, an installed `openchamber`, or
  `bunx @openchamber/web`.
- Automatic registration of WebStorm's MCP server in OpenCode, so the agent can
  call WebStorm tools: inspections and diagnostics (`get_file_problems`,
  `lint_files`), symbol and text search, rename refactoring, run
  configurations, terminal commands, file reads and patches.
- Settings for the server URL, port, command, MCP port, MCP transport, and
  startup behavior.

WebStorm does not expose a standalone LSP endpoint. The MCP tools run on
WebStorm's PSI and inspection engine, which covers diagnostics, symbol lookup
and refactoring. OpenCode's own LSP remains available for automatic diagnostics
after edits. The WebStorm tools are pull-based: the model calls
`get_file_problems` or `lint_files` when it needs them.

## Requirements

- WebStorm 2026.2. The plugin is built for build 262.
- To build: JDK 25 and the included Gradle wrapper. WebStorm's bundled JBR works.
- At runtime: Bun or an installed `openchamber` CLI. OpenCode 2.x must be
  installed for the agent backend.

## Build

```bash
cd packages/webstorm
JAVA_HOME="$HOME/Applications/WebStorm.app/Contents/jbr/Contents/Home" \
  ./gradlew buildPlugin
```

The plugin zip is written to
`packages/webstorm/build/distributions/openchamber-webstorm-0.1.0.zip`.

`verifyPlugin` checks the plugin against the installed WebStorm:

```bash
JAVA_HOME="$HOME/Applications/WebStorm.app/Contents/jbr/Contents/Home" \
  ./gradlew verifyPlugin
```

If WebStorm lives somewhere else, pass
`-PwebstormPath=/path/to/WebStorm.app` or set `WEBSTORM_HOME`.

## Install

1. Open **Settings | Plugins**.
2. Open the gear menu and choose **Install Plugin from Disk**.
3. Select the zip from the build output.
4. Restart WebStorm.

Open the panel from the right sidebar or **Tools | Open OpenChamber**.

## First run

The plugin starts the server when the tool window opens. With the OpenChamber
repository open and trusted, and its `packages/web/package.json` naming
`@openchamber/web`, it runs `bun packages/web/bin/cli.js`. In another project it
looks for `openchamber` on `PATH` and then `bunx @openchamber/web`. If the
repository is not trusted, set **Server command** to
`bun /path/to/openchamber/packages/web/bin/cli.js`.

To use WebStorm's tools from OpenCode:

1. Open **Settings | Tools | MCP Server** and enable the MCP server. Confirm the
   consent dialog.
2. Open the OpenChamber tool window and click **Connect MCP**.
3. Start a new OpenCode session. The `webstorm` MCP server should be available.

The plugin also tries the registration automatically when the server becomes
ready and the setting is enabled. If the MCP server is off, it shows a warning
with a link to Settings.

## Settings

**Settings | Tools | OpenChamber**

- **External server URL**: use an already running OpenChamber instead of
  starting one.
- **Server port**: port for the managed server. Default `3001`. If the port is
  taken, the plugin attaches to a healthy OpenChamber there or picks a nearby
  free port.
- **Server command**: optional launcher without the `serve` flags, for example
  `openchamber` or `bun /path/to/packages/web/bin/cli.js`. The plugin appends
  `serve --foreground --host 127.0.0.1 --port <port>`.
- **MCP server port**: WebStorm's MCP port. Default `64342`.
- **MCP transport**: `sse` or `stream`.
- **Auto-connect**: register the MCP server when OpenChamber becomes ready.
- **Open on project startup**: open the tool window when a project loads.

## How MCP registration works

The plugin calls OpenChamber's config API at
`POST /api/config/mcp/webstorm`, with a fallback to `PATCH` when the entry
already exists. OpenChamber writes the entry to OpenCode's `mcp.servers` config
and OpenCode reloads it. The entry is a remote server at
`http://127.0.0.1:<mcp-port>/sse` with `oauth: false` and an explicit
`protocol`: `legacy` for SSE, `auto` for streamable HTTP. The `stream` transport
uses `/stream`.

When `OPENCHAMBER_UI_PASSWORD` is set, the plugin logs in at `/auth/session`
with that password and sends the returned client token with the config request.
When the IDE runs with `IJ_MCP_AUTH_TOKEN`, the entry includes an
`Authorization: Bearer` header for WebStorm's MCP server.

The plugin never edits `opencode.json` directly.

## Troubleshooting

- **The panel says the server failed to start**: check the log excerpt in the
  panel. The server command may be missing, or the port may be unavailable.
- **The MCP entry is not created**: enable **Settings | Tools | MCP Server**
  first. If WebStorm picked a different MCP port, set it in OpenChamber
  settings and click **Connect MCP** again. If OpenChamber requires a
  UI password, set `OPENCHAMBER_UI_PASSWORD` before starting WebStorm.
- **The panel says the server stopped responding**: the plugin checks `/health`
  every five seconds and marks the server failed after three misses. Click
  **Restart**.
- **A leftover server stays on the port after force-quitting WebStorm**: start
  WebStorm again. The plugin attaches to the healthy server on the configured
  port. Stop it with `openchamber stop --port <port>` if needed.
- **The UI asks for a password**: `OPENCHAMBER_UI_PASSWORD` was set in the
  environment. Enter the password in the JCEF page.

Plugin logs are in `idea.log` under `#com.fsodano.openchamber`.

## Limits

- The tool window hosts the web surface. It does not implement the VS Code
  extension bridge, so editor-specific VS Code affordances are absent.
- The plugin does not enable WebStorm's MCP server for you. WebStorm requires
  explicit consent for third-party tool access.
- WebStorm MCP diagnostics are requested by the model. They are not pushed into
  every edit the way a language server's diagnostics are.

## Development

The package is intentionally outside the Bun workspace. It has no
`package.json`, so `bun install` ignores it while the Kotlin build stays
self-contained.
