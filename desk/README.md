# Orbit Desk (optional)

A tiny program for your computer that connects it to Orbit. It does two things:

1. **Screen pictures.** Orbit can look at your screen when you ask ("what do you think of
   this?", "can you help with this error?") and gets a real screenshot instead of a photo from
   its camera, so text is crisp. Orbit only looks when you ask; every look is listed in the
   Orbit Desk window, and you can pause sharing any time.
2. **News from your coding agents.** When a coding agent or a script on this computer finishes,
   gets stuck or needs your decision, Orbit looks at you and tells you, in its own voice. If
   you're away from your desk, it tells you when it sees you come back. It works with **any**
   agent: through MCP (Antigravity, Cursor, Codex, Claude Code, VS Code, Gemini CLI…), a
   command, or a local web request. [Set it up below.](#coding-agents)

Everything stays on your home network until Orbit sends something to Gemini (a screen picture
you asked about, or the few words of news it speaks).

## Set up (once)

1. **Install Python 3** (free) from <https://www.python.org/downloads/>.
   On Windows, tick **"Add python.exe to PATH"** on the first installer screen.
2. Download this `desk` folder (or the whole Orbit project) to the computer.
3. **Start it:**
   - **Windows:** double-click `run_orbit_desk.bat`.
   - **Mac / Linux:** open a terminal in this folder and run
     `pip3 install -r requirements.txt` then `python3 orbit_desk.py`.
     (Mac: the first time, allow **Screen Recording** for Terminal in System Settings →
     Privacy & Security.)
4. **Windows Firewall** may ask whether Python can use the network: allow it on
   **Private networks** (your home Wi-Fi). Without this the phone can't reach it.
5. The window shows the computer's name, its address and a **6-digit pairing code**.
6. **On Orbit:** long-press the eyes → **Computer** tab → switch on **Orbit Desk on my
   computer** → **Find my computer** → tap your computer → type the pairing code → **Test**.
   You should see **✓ Connected to (your computer)**.
7. Ask Orbit: *"What do you think of what's on my screen?"* Or, on the computer, try the agent
   news: `python orbit_desk.py notify done "Hello from my computer"` (Windows: `py` instead of
   `python`).

## Coding agents

Agents reach Orbit through Orbit Desk, so **Orbit Desk must be running** (step 3 above). Pick
whichever way your tools support; you can use several.

Below, `/path/to/orbit_desk.py` means wherever you put this folder, for example
`C:\Users\you\Orbit\desk\orbit_desk.py` on Windows (in JSON, write each `\` as `\\`), or
`/Users/you/Orbit/desk/orbit_desk.py` on a Mac. On Windows, use `py` where it says `python`.

### MCP: works with almost every agent

Orbit Desk includes an MCP server. Agents start it themselves; it gives them three tools:

| Tool | What the agent can do |
|---|---|
| `orbit_notify` | Tell you something: `done`, `needs_input`, `failed` or `info`, with a short message. Orbit looks at you, reacts, and says it (or tells you when you're back). |
| `orbit_status` | Check whether you're at your desk, and whether Orbit is asleep, in a conversation or in focus mode, before deciding to wait. |
| `orbit_express` | A reaction without words: `nod`, `celebrate`, `happy`… |

**Antigravity, Cursor, Gemini CLI, Claude Desktop and most others** use the same settings
shape. Add this to their MCP settings (Antigravity: Agent panel → … → MCP Servers → Manage →
View raw config; Cursor: `~/.cursor/mcp.json`; Gemini CLI: `~/.gemini/settings.json`):

```json
{
  "mcpServers": {
    "orbit": {
      "command": "python",
      "args": ["/path/to/orbit_desk.py", "mcp", "--source", "Antigravity"]
    }
  }
}
```

`--source` is the name Orbit says ("Antigravity is done…"); change it per tool.

**Codex (OpenAI)**: in `~/.codex/config.toml`:

```toml
[mcp_servers.orbit]
command = "python"
args = ["/path/to/orbit_desk.py", "mcp", "--source", "Codex"]
```

**VS Code (Copilot agent mode)**: in `.vscode/mcp.json`:

```json
{ "servers": { "orbit": { "type": "stdio", "command": "python",
  "args": ["/path/to/orbit_desk.py", "mcp", "--source", "Copilot"] } } }
```

**Claude Code**: `claude mcp add orbit -- python /path/to/orbit_desk.py mcp --source "Claude Code"`

**Then tell the agent when to use it.** Agents call tools when they think it helps; one line in
your project's rules file makes it dependable. Add this to `AGENTS.md` (Codex, Cursor, and many
others read it), `GEMINI.md`, `CLAUDE.md` or your Antigravity rules:

> When you finish a task that took more than a minute, or you're blocked waiting for my
> decision, call `orbit_notify` with a short spoken-friendly message (under 15 words, no code).

### Automatic: hooks that fire every time

Some agents can run a command at fixed moments, which is more reliable than asking the model.
Orbit Desk's `hook` command reads what the agent passes in and never changes what the agent
does (it prints nothing and always succeeds, even if Orbit Desk isn't running).

**Codex**: in `~/.codex/config.toml` (Codex runs it when a turn finishes):

```toml
notify = ["python", "/path/to/orbit_desk.py", "hook", "--source", "Codex"]
```

**Claude Code**: in `~/.claude/settings.json`: `Stop` = finished, `Notification` = it needs
your permission or has been waiting for you:

```json
{
  "hooks": {
    "Stop": [{ "hooks": [{ "type": "command",
      "command": "python /path/to/orbit_desk.py hook --source \"Claude Code\"" }] }],
    "Notification": [{ "hooks": [{ "type": "command",
      "command": "python /path/to/orbit_desk.py hook --source \"Claude Code\"" }] }]
  }
}
```

**Other agents with hooks** (Cursor, Gemini CLI, Windsurf…): point their "agent stopped" or
"task finished" hook at the same command. It understands their JSON when the event name
contains stop, finish, complete or done; for anything else, use the plain command below.

### From any script or terminal

```sh
python orbit_desk.py notify done "Build finished"
python orbit_desk.py notify needs_input "Which database should I use?" --source Codex
python orbit_desk.py notify failed "Deploy failed"
python orbit_desk.py express celebrate
python orbit_desk.py status
```

For example, after a long job: `make release && python orbit_desk.py notify done "Release built" || python orbit_desk.py notify failed "Release build failed"`.

### From anything else: a local web request

```sh
curl -X POST http://127.0.0.1:47800/agent/notify -H "X-Orbit-Agent: 1" \
     -H "Content-Type: application/json" \
     -d '{"kind": "done", "message": "Tests pass", "source": "My script"}'
```

### What Orbit does with the news

| Kind | Orbit | Out loud |
|---|---|---|
| `done` | A happy look and a twinkle | "Codex is done: all tests pass." |
| `needs_input` | An alert look and a chime; the eyes' edge **glows blue** until it has told you | "Cursor needs you: which database should I use?" |
| `failed` | A worried look | "Antigravity hit a problem: the deploy failed." |
| `info` | A perk | "Your agent says: …" |

- **Away from your desk?** It keeps the news and tells you when it sees you come back ("While
  you were away: Codex is done. Cursor needs you.").
- **In a conversation, or asleep?** It waits until it's done or awake.
- **Focus session?** Finished and FYI news is shown quietly; "needs you" and problems still speak.
- **Saying it out loud** can be switched off on Orbit (Computer tab), leaving just the look and
  the sound.

## While it runs

| Type in the window, then Enter | Does |
|---|---|
| `p` | Pause / resume screen sharing (Orbit gets "sharing is paused") |
| `a` | Mute / unmute agents (they're told "agents are muted") |
| `q` | Quit |

Each look at your screen and each piece of agent news is listed in the window.

Options: `--monitor 2` shares your second screen (`--monitor 0` = all screens together);
`--new-code` makes a new pairing code (Orbit will need it); `--port 47900` uses another port.

To start it automatically on Windows: press Win + R, type `shell:startup`, and put a shortcut
to `run_orbit_desk.bat` in the folder that opens.

## If Orbit can't find it

- The phone and computer must be on the **same Wi-Fi network** (guest networks usually keep
  devices apart).
- Check the firewall question above (Windows: Settings → Privacy & security → Windows Security →
  Firewall & network protection → Allow an app through firewall → Python → Private).
- Type the address shown in the window (like `192.168.1.23:47800`) into Orbit by hand.

## Privacy and security

- Pictures go only to a phone that knows the pairing code. After 5 wrong codes in a minute it
  refuses everyone for a minute.
- The pairing code is kept in `.orbit_desk.json` in your home folder. Delete it, or run with
  `--new-code`, to make a new one.
- On your home network the picture travels unencrypted (like most home gadgets). Don't run it
  on public or shared networks.
- **Agents** can only reach Orbit from this computer (requests from other machines on the
  network are refused), and never from a web page (a page in your browser can't add the
  `X-Orbit-Agent` header, and its requests carry an `Origin`, which is refused).
- **Agents can only send news and reactions:** never take photos, listen, see your screen or
  start a conversation. Orbit says the news with Gemini's voice only (no conversation, no tools),
  so even a message written to trick an AI can't make Orbit do anything else.
- News is limited to 200 characters, 12 a minute, and is dropped if Orbit hasn't collected it
  within an hour.
- **Work computers:** your employer's rules apply to sharing what's on your screen with an AI
  service, and to what your agents' news says. If in doubt, don't.

## How it works (for tinkerers)

Plain HTTP on port 47800. For Orbit, with the pairing code in an `X-Orbit-Code` header:

| Path | Answer |
|---|---|
| `GET /hello` | `{"app": "orbit-desk", "name": …, "version": 2}`, no code needed |
| `GET /check` | `200 {"ok": true, "name": …, "paused": …}`, or `401` (wrong code) / `429` (too many tries) |
| `GET /screen` | `200` JPEG (longest side 1600 px), or `401` / `423` (paused) / `429` / `500` |
| `GET /events?since=N&boot=B&present=1&…` | Waits up to 15 s for news newer than `N`, then `{"events": […], "next": N, "boot": B}`. Orbit also reports how it's doing (`present`, `seen`, `asleep`, `talking`, `focus`, `waiting`), for `orbit_status`. A different `boot` (Orbit Desk restarted) starts from the beginning. |

For agents, from this computer only, with the header `X-Orbit-Agent: 1` and no `Origin`:

| Path | Body / answer |
|---|---|
| `POST /agent/notify` | `{"kind": "done" \| "needs_input" \| "failed" \| "info", "message": "…", "source": "…"}` → `{"ok": true, "id": …, "orbit": {status}}` |
| `POST /agent/express` | `{"move": "nod", "source": "…"}` (moves: nod, shake_head, happy, celebrate, surprised, curious, perk, sad, wink, ponder, love, boing) |
| `GET /agent/status` | `{"orbit_connected": …, "person_at_desk": …, "seconds_since_person_seen": …, "orbit_asleep": …, "in_conversation": …, "focus_mode": …, "news_waiting_for_person": …}` |

The MCP server (`orbit_desk.py mcp`) speaks MCP over stdio (protocol versions 2024-11-05 to
2025-06-18) and passes tool calls to these. Tests: `python -m unittest discover -s desk`.

Finding computers: Orbit broadcasts `ORBIT-DESK?` (UDP, port 47801); Orbit Desk replies
`ORBIT-DESK <port> <computer name>`. You can build your own Orbit Desk (say, a tray app) as
long as it answers the same way.
