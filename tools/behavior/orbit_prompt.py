"""Orbit's real instructions and tools, read straight from the app's Kotlin source.

The behaviour bench must test exactly what the app ships, so nothing here is copied by hand:
every sentence Gemini is given comes out of GeminiLive.kt and Settings.kt each time the bench
runs. If a constant or paragraph can't be found (it was renamed, moved or reworded beyond
recognition), this module raises ExtractionError instead of guessing: that means the app
changed and the bench needs a matching update.

What's covered (the parts of GeminiLive.instructions() that matter for the scenarios, in the
same order the app uses):
    name intro + nicknames, personality, TRUTHFUL, the woken-by-name note (when applicable),
    LANGUAGE_RULE, QUIET_RULES, the date line, the expressions paragraph, the "tools work
    silently" paragraph, the vision paragraph.
Left out on purpose (they depend on the owner's data, or on features the scenarios don't use):
    web search, weather, screen looks, reminders, memory, diary, friendship, focus, birthday,
    the week's moments, extra context, inner life.

Run it directly to print what the bench sends:  python3 tools/behavior/orbit_prompt.py
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Callable, Mapping, Optional, Union

REPO_ROOT = Path(__file__).resolve().parents[2]
APP_SRC = REPO_ROOT / "app/src/main/java/com/example/companion2"

# The Live API endpoint, exactly as GeminiLive.start() builds it (the key goes in a header)
LIVE_URL = ("wss://generativelanguage.googleapis.com/ws/"
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
KEY_HEADER = "x-goog-api-key"


class ExtractionError(RuntimeError):
    """The app's source no longer has something the bench relies on."""


# ----------------------------------------------------------------------------------------------
# A small reader for Kotlin string expressions:  "..." + "..." with \" escapes, $NAME and ${...}
# ----------------------------------------------------------------------------------------------

@dataclass
class Chain:
    """One Kotlin string expression: literals joined with +, possibly with templates."""
    start: int                      # offset of the first quote in the source
    end: int                        # offset just after the last literal
    parts: list = field(default_factory=list)  # ("text", str) | ("tmpl", expr, braced)

    def display(self) -> str:
        """The text with templates shown as written ($NAME or ${expr}), for finding chains."""
        out = []
        for p in self.parts:
            if p[0] == "text":
                out.append(p[1])
            else:
                out.append("${" + p[1] + "}" if p[2] else "$" + p[1])
        return "".join(out)

    def templates(self) -> list:
        return [p[1] for p in self.parts if p[0] == "tmpl"]

    def render(self, values: Union[Mapping[str, str], Callable[[str], str], None] = None) -> str:
        """The finished text. Each template is looked up in `values` by its expression, or by
        the expression's leading dotted name (settings.companionName.trim()... → settings.companionName)."""
        out = []
        for p in self.parts:
            if p[0] == "text":
                out.append(p[1])
                continue
            out.append(_resolve(p[1], values))
        return "".join(out)


def _resolve(expr: str, values) -> str:
    if callable(values):
        return values(expr)
    values = values or {}
    if expr in values:
        return values[expr]
    lead = re.match(r"[A-Za-z_][\w.]*", expr)
    name = lead.group(0) if lead else expr
    while name:
        if name in values:
            return values[name]
        name = name.rpartition(".")[0]
    raise ExtractionError(f"No value for the Kotlin template ${{{expr}}}")


_ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", '"': '"', "'": "'", "\\": "\\", "$": "$"}


def _skip_space_and_comments(src: str, i: int) -> int:
    while i < len(src):
        if src[i].isspace():
            i += 1
        elif src.startswith("//", i):
            nl = src.find("\n", i)
            i = len(src) if nl < 0 else nl + 1
        elif src.startswith("/*", i):
            close = src.find("*/", i + 2)
            if close < 0:
                raise ExtractionError("Unclosed /* comment in Kotlin source")
            i = close + 2
        else:
            break
    return i


def _skip_braced(src: str, i: int) -> int:
    """i is just after the '{' of ${ ... }. Returns the offset of the matching '}'."""
    depth = 1
    while i < len(src):
        c = src[i]
        if c == '"':
            # a string inside the template, e.g. ifBlank { "Orbit" }
            _, i = _read_literal(src, i)
            continue
        if c == "'":
            close = src.find("'", i + 1 + (src[i + 1] == "\\"))
            i = close + 1
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise ExtractionError("Unclosed ${...} template in Kotlin source")


def _read_literal(src: str, i: int):
    """Reads one "..." literal starting at src[i] == '"'. Returns (parts, offset after it)."""
    if src.startswith('"""', i):
        raise ExtractionError(f"Raw (triple-quoted) Kotlin strings aren't supported (offset {i})")
    assert src[i] == '"'
    i += 1
    parts, buf = [], []
    while True:
        if i >= len(src):
            raise ExtractionError("Unterminated Kotlin string literal")
        c = src[i]
        if c == '"':
            i += 1
            break
        if c == "\n":
            raise ExtractionError(f"Line break inside a Kotlin string literal (offset {i})")
        if c == "\\":
            nxt = src[i + 1]
            if nxt == "u":
                buf.append(chr(int(src[i + 2:i + 6], 16)))
                i += 6
                continue
            if nxt not in _ESCAPES:
                raise ExtractionError(f"Unknown Kotlin escape \\{nxt}")
            buf.append(_ESCAPES[nxt])
            i += 2
            continue
        if c == "$":
            m = re.match(r"[A-Za-z_]\w*", src[i + 1:])
            if src.startswith("${", i):
                close = _skip_braced(src, i + 2)
                if buf:
                    parts.append(("text", "".join(buf)))
                    buf = []
                parts.append(("tmpl", src[i + 2:close].strip(), True))
                i = close + 1
                continue
            if m:
                if buf:
                    parts.append(("text", "".join(buf)))
                    buf = []
                parts.append(("tmpl", m.group(0), False))
                i += 1 + len(m.group(0))
                continue
        buf.append(c)
        i += 1
    if buf:
        parts.append(("text", "".join(buf)))
    return parts, i


def read_chain(src: str, i: int) -> Chain:
    """Reads "a" + "b" + ... starting at the first quote. Stops at anything that isn't another
    literal after a +, e.g. `+ if (...) "x" else ""` stops before the +."""
    i = _skip_space_and_comments(src, i)
    if i >= len(src) or src[i] != '"':
        raise ExtractionError(f"Expected a string literal at offset {i}: {src[i:i + 40]!r}")
    chain = Chain(start=i, end=i)
    parts, i = _read_literal(src, i)
    chain.parts.extend(parts)
    chain.end = i
    while True:
        j = _skip_space_and_comments(src, i)
        if j < len(src) and src[j] == "+":
            k = _skip_space_and_comments(src, j + 1)
            if k < len(src) and src[k] == '"' and not src.startswith('"""', k):
                parts, i = _read_literal(src, k)
                chain.parts.extend(parts)
                chain.end = i
                continue
        break
    # merge neighbouring text parts so "ab" + "cd" is one piece
    merged = []
    for p in chain.parts:
        if merged and p[0] == "text" and merged[-1][0] == "text":
            merged[-1] = ("text", merged[-1][1] + p[1])
        else:
            merged.append(p)
    chain.parts = merged
    return chain


def all_chains(src: str) -> list:
    """Every string expression in a piece of Kotlin, in order (skipping comments and chars)."""
    chains, i = [], 0
    while i < len(src):
        c = src[i]
        if src.startswith("//", i) or src.startswith("/*", i):
            i = _skip_space_and_comments(src, i)
            continue
        if c == "'":
            i = src.find("'", i + 1 + (src[i + 1] == "\\")) + 1
            continue
        if src.startswith('"""', i):
            close = src.find('"""', i + 3)
            i = len(src) if close < 0 else close + 3
            continue
        if c == '"':
            chain = read_chain(src, i)
            chains.append(chain)
            i = chain.end
            continue
        i += 1
    return chains


def find_chain(src: str, anchor: str, what: str) -> Chain:
    """The one string expression in `src` whose text contains `anchor` (templates as written)."""
    found = [c for c in all_chains(src) if anchor in c.display()]
    if not found:
        raise ExtractionError(f"Couldn't find {what} (text containing {anchor!r}). The app changed: "
                              "update tools/behavior/orbit_prompt.py to match.")
    if len(found) > 1:
        raise ExtractionError(f"Found {len(found)} strings containing {anchor!r} for {what}; "
                              "make the anchor more specific.")
    return found[0]


def const_chain(src: str, name: str, file_label: str = "source") -> Chain:
    """`const val NAME = "..." + "..."` (or plain `val`)."""
    m = re.search(r"^\s*(?:const\s+)?val\s+" + re.escape(name) + r"\s*=\s*", src, re.M)
    if not m:
        raise ExtractionError(f"Couldn't find the constant {name} in {file_label}. The app changed: "
                              "update tools/behavior/orbit_prompt.py to match.")
    return read_chain(src, m.end())


def const_string(src: str, name: str, file_label: str = "source", values=None) -> str:
    chain = const_chain(src, name, file_label)
    if chain.templates() and values is None:
        raise ExtractionError(f"{name} contains templates {chain.templates()}; values needed")
    return chain.render(values)


def function_body(src: str, signature_regex: str, label: str) -> str:
    """The text of a Kotlin function, from its signature to its closing brace (or, for
    expression bodies like `= buildString { ... }` / `= JSONObject()...`, to the next fun)."""
    m = re.search(signature_regex, src)
    if not m:
        raise ExtractionError(f"Couldn't find {label} in the source. The app changed: update "
                              "tools/behavior/orbit_prompt.py to match.")
    start = m.start()
    nxt = re.compile(r"^\s*(?:private |internal |override |public )*fun ", re.M).search(src, m.end())
    return src[start:nxt.start() if nxt else len(src)]


def string_list(src: str, name: str, file_label: str) -> list:
    """`val NAME = listOf("a", "b", ...)`."""
    m = re.search(r"\bval\s+" + re.escape(name) + r"\s*=\s*listOf\(", src)
    if not m:
        raise ExtractionError(f"Couldn't find the list {name} in {file_label}.")
    depth, i = 1, m.end()
    while depth and i < len(src):
        if src[i] == '"':
            _, i = _read_literal(src, i)
            continue
        depth += {"(": 1, ")": -1}.get(src[i], 0)
        i += 1
    items = [c.render() for c in all_chains(src[m.end():i - 1])]
    if not items:
        raise ExtractionError(f"The list {name} in {file_label} is empty")
    return items


def string_map(src: str, name: str, file_label: str) -> dict:
    """`val NAME = linkedMapOf("Key" to "..." + "...", ...)`."""
    m = re.search(r"\bval\s+" + re.escape(name) + r"\s*=\s*(?:linkedMapOf|mapOf|hashMapOf)\(", src)
    if not m:
        raise ExtractionError(f"Couldn't find the map {name} in {file_label}.")
    result, i = {}, m.end()
    while True:
        i = _skip_space_and_comments(src, i)
        if src[i] == ")":
            break
        key = read_chain(src, i)
        i = _skip_space_and_comments(src, key.end)
        if not src.startswith("to", i):
            raise ExtractionError(f"Unexpected text in {name}: {src[i:i + 30]!r}")
        value = read_chain(src, i + 2)
        result[key.render()] = value.render()
        i = _skip_space_and_comments(src, value.end)
        if src[i] == ",":
            i += 1
    if not result:
        raise ExtractionError(f"The map {name} in {file_label} is empty")
    return result


# ----------------------------------------------------------------------------------------------
# Orbit's pieces
# ----------------------------------------------------------------------------------------------

@dataclass
class OrbitSource:
    """Everything the bench needs from the app, read from the Kotlin files."""
    model: str
    voice: str
    constants: dict             # TRUTHFUL, PHOTO_RULES, LANGUAGE_RULE, QUIET_RULES, GREETING
    personalities: dict
    default_personality: str
    expressions: list
    paragraphs: dict            # inline instruction paragraphs, as Chains (rendered later)
    look_texts: dict            # wording the app uses when answering a look
    express_tool: dict
    look_tool: dict


def _read(path: Path) -> str:
    if not path.exists():
        raise ExtractionError(f"{path} doesn't exist (was it renamed?)")
    return path.read_text(encoding="utf-8")


def load(src_dir: Path = APP_SRC) -> OrbitSource:
    live_path, settings_path = src_dir / "GeminiLive.kt", src_dir / "Settings.kt"
    live, settings = _read(live_path), _read(settings_path)
    expressions_src = _read(src_dir / "Expressions.kt") if (src_dir / "Expressions.kt").exists() else ""

    constants = {
        name: const_string(live, name, "GeminiLive.kt")
        for name in ("TRUTHFUL", "PHOTO_RULES", "LANGUAGE_RULE", "QUIET_RULES")
    }
    constants["GREETING"] = const_string(settings, "GREETING", "Settings.kt")
    model = const_string(settings, "GEMINI_MODEL", "Settings.kt")

    personalities = string_map(settings, "PERSONALITIES", "Settings.kt")
    m = re.search(r"val\s+DEFAULT_PERSONALITY\s*=\s*PERSONALITIES\.getValue\(\s*\"([^\"]+)\"\s*\)", settings)
    if not m or m.group(1) not in personalities:
        raise ExtractionError("Couldn't work out DEFAULT_PERSONALITY in Settings.kt")
    default_personality = personalities[m.group(1)]

    m = re.search(r"var\s+voice\s+by\s+text\(\s*\"voice\"\s*,\s*\"([^\"]+)\"\s*\)", settings)
    if not m:
        raise ExtractionError("Couldn't find the default voice (var voice by text(\"voice\", ...)) in Settings.kt")
    voice = m.group(1)

    # EXPRESSIONS: in Settings.kt today; Expressions.kt is checked too in case it moves there
    expressions = None
    for text, label in ((settings, "Settings.kt"), (expressions_src, "Expressions.kt")):
        if re.search(r"\bval\s+EXPRESSIONS\s*=", text):
            expressions = string_list(text, "EXPRESSIONS", label)
            break
    if not expressions:
        raise ExtractionError("Couldn't find val EXPRESSIONS = listOf(...) in Settings.kt or Expressions.kt")

    # The instruction paragraphs written inline in instructions(), found by a few words each.
    # They're checked to still be in the same order as in the app.
    body = function_body(live, r"private fun instructions\(\)", "GeminiLive.instructions()")
    anchors = [
        ("name", "Your name is "),
        ("nicknames", "People call you Orbit or Orby."),
        ("truthful", "$TRUTHFUL"),
        ("woken", "They've just called you by name"),
        ("language", "$LANGUAGE_RULE"),
        ("quiet", "$QUIET_RULES"),
        ("date", "It is currently $now."),
        ("expressions", "You have expressive eyes."),
        ("tools_silent", "Your tools work silently in the background."),
        ("vision", "You can see through your camera with the look tool"),
    ]
    paragraphs = {key: find_chain(body, anchor, f"the {key} part of instructions()") for key, anchor in anchors}
    order = [paragraphs[k].start for k, _ in anchors]
    if order != sorted(order):
        raise ExtractionError("The paragraphs in GeminiLive.instructions() were reordered; update "
                              "the order in tools/behavior/orbit_prompt.py to match.")
    if not re.search(r"append\(\s*settings\.personality\s*\)", body):
        raise ExtractionError("instructions() no longer appends settings.personality right after the name")
    m = re.search(r'ofPattern\("([^"]+)"', body)
    if not m:
        raise ExtractionError("Couldn't find the date format in instructions()")
    if m.group(1) != "EEEE d MMMM yyyy, h:mm a (zzzz)":
        raise ExtractionError(f"The date format in instructions() changed to {m.group(1)!r}; "
                              "update orbit_prompt.date_text() to match")

    # How the app answers a look (lookAndAnswer): photo caption, the result, the no-neck extra
    look_fn = function_body(live, r"private fun lookAndAnswer\(", "GeminiLive.lookAndAnswer()")
    look_texts = {
        "where_tilting": find_chain(look_fn, "with your head tilted down", "the tilting photo wording").render(),
        "where_no_neck": find_chain(look_fn, "you have no neck to tilt down", "the no-neck photo wording").render(),
        "where_ahead": find_chain(look_fn, "straight ahead, at them", "the looking-ahead wording").render(),
        "caption": find_chain(look_fn, "This is the photo you just took with your camera", "the photo caption"),
        "result": find_chain(look_fn, "is in the conversation just above. $PHOTO_RULES", "the look result"),
        "no_neck_extra": find_chain(look_fn, "If their desk isn't in the photo", "the no-neck extra").render(),
    }

    express_tool = _express_tool(live, expressions)
    look_tool = _look_tool(live)
    return OrbitSource(model, voice, constants, personalities, default_personality, expressions,
                       paragraphs, look_texts, express_tool, look_tool)


def _express_tool(live: str, expressions: list) -> dict:
    body = function_body(live, r"private fun expressTool\(\)", "GeminiLive.expressTool()")
    if '.put("name", "express")' not in body:
        raise ExtractionError("expressTool() no longer declares a tool called express")
    description = find_chain(body, "Show a feeling or gesture", "the express tool's description").render()
    if "JSONArray(EXPRESSIONS)" not in body or '"required", JSONArray().put("expression")' not in body:
        raise ExtractionError("expressTool()'s parameters changed shape; update _express_tool()")
    tool = {
        "name": "express",
        "description": description,
        "parameters": {
            "type": "object",
            "properties": {"expression": {"type": "string", "enum": list(expressions)}},
            "required": ["expression"],
        },
    }
    if '.put("behavior", "NON_BLOCKING")' in body:
        tool["behavior"] = "NON_BLOCKING"
    return tool


def _look_tool(live: str) -> dict:
    start = live.find('.put("name", "look")')
    if start < 0:
        raise ExtractionError("setupMessage() no longer declares a tool called look")
    # the declaration runs until the next tool is added
    stop = live.find("functions.put(", start)
    block = live[start:stop if stop > 0 else len(live)]
    description = find_chain(block, "Take one photo with your camera", "the look tool's description").render()
    where = find_chain(block, '"ahead" (default)', "the look tool's where description").render()
    m = re.search(r'"where"\s*,\s*JSONObject\(\)\s*\.put\("type",\s*"string"\)\s*'
                  r'\.put\("enum",\s*JSONArray\(\)((?:\.put\("[^"]+"\))+)\)', block)
    if not m:
        raise ExtractionError("The look tool's where parameter changed shape; update _look_tool()")
    enum = re.findall(r'\.put\("([^"]+)"\)', m.group(1))
    tool = {
        "name": "look",
        "description": description,
        "parameters": {
            "type": "object",
            "properties": {"where": {"type": "string", "enum": enum, "description": where}},
        },
    }
    if '"behavior"' in block:
        raise ExtractionError("The look tool now has a behavior setting; update _look_tool() to copy it")
    return tool


# ----------------------------------------------------------------------------------------------
# Assembling what the app sends
# ----------------------------------------------------------------------------------------------

def date_text(now: Optional[datetime] = None) -> str:
    """Kotlin's "EEEE d MMMM yyyy, h:mm a (zzzz)" in English, e.g.
    "Thursday 12 November 2026, 7:23 AM (Coordinated Universal Time)"."""
    now = now or datetime.now().astimezone()
    zone = now.tzname() or "Coordinated Universal Time"
    if zone in ("UTC", "GMT"):
        zone = "Coordinated Universal Time"
    hour = now.hour % 12 or 12
    return f"{now:%A} {now.day} {now:%B %Y}, {hour}:{now:%M} {'AM' if now.hour < 12 else 'PM'} ({zone})"


@dataclass
class Options:
    """App settings the bench copies. Defaults match a fresh install."""
    name: str = "Orbit"
    personality: Optional[str] = None   # None = DEFAULT_PERSONALITY
    woken_by_name: bool = False         # started by its name (no greeting)
    expressions: bool = True
    vision: bool = True
    natural: bool = True                # enableAffectiveDialog + proactiveAudio ("natural talk")
    search: bool = False                # the app adds googleSearch when web search is on
    voice: Optional[str] = None         # None = the app's default voice
    now: Optional[datetime] = None


def instructions(src: OrbitSource, opts: Options = Options()) -> str:
    p = src.paragraphs
    values = {
        "settings.companionName": opts.name,
        "TRUTHFUL": src.constants["TRUTHFUL"],
        "LANGUAGE_RULE": src.constants["LANGUAGE_RULE"],
        "QUIET_RULES": src.constants["QUIET_RULES"],
        "now": date_text(opts.now),
    }
    out = [p["name"].render(values), p["nicknames"].render(values),
           opts.personality or src.default_personality, p["truthful"].render(values)]
    if opts.woken_by_name:
        out.append(p["woken"].render(values))
    out += [p["language"].render(values), p["quiet"].render(values), p["date"].render(values)]
    if opts.expressions:
        out.append(p["expressions"].render(values))
    out.append(p["tools_silent"].render(values))
    if opts.vision:
        out.append(p["vision"].render(values))
    return "".join(out)


def function_declarations(src: OrbitSource, opts: Options = Options()) -> list:
    tools = []
    if opts.expressions:
        tools.append(json.loads(json.dumps(src.express_tool)))
    if opts.vision:
        tools.append(json.loads(json.dumps(src.look_tool)))
    return tools


def setup_message(src: OrbitSource, opts: Options = Options()) -> dict:
    """The {"setup": ...} message, in the same shape as GeminiLive.setupMessage()."""
    generation = {"responseModalities": ["AUDIO"]}
    if opts.natural:
        generation["enableAffectiveDialog"] = True
    generation["speechConfig"] = {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": opts.voice or src.voice}}}
    setup = {
        "model": f"models/{src.model}",
        "contextWindowCompression": {"slidingWindow": {}},
        "sessionResumption": {},
        "generationConfig": generation,
        "systemInstruction": {"parts": [{"text": instructions(src, opts)}]},
    }
    if opts.natural:
        setup["proactivity"] = {"proactiveAudio": True}
    tools = []
    functions = function_declarations(src, opts)
    if functions:
        tools.append({"functionDeclarations": functions})
    if opts.search:
        tools.append({"googleSearch": {}})
    if tools:
        setup["tools"] = tools
    setup["outputAudioTranscription"] = {}
    setup["inputAudioTranscription"] = {}
    return {"setup": setup}


def look_caption_and_result(src: OrbitSource, mode: str = "no_neck") -> tuple:
    """What the app sends after a photo: (caption for the photo turn, the look tool's result).
    mode: "no_neck" (look down without a neck: the photo is straight ahead), "tilting"
    (with a neck), or "ahead"."""
    where = {"no_neck": src.look_texts["where_no_neck"], "tilting": src.look_texts["where_tilting"],
             "ahead": src.look_texts["where_ahead"]}[mode]
    values = {"what": where, "PHOTO_RULES": src.constants["PHOTO_RULES"]}
    caption = src.look_texts["caption"].render(values)
    result = src.look_texts["result"].render(values)
    if mode == "no_neck":
        result += src.look_texts["no_neck_extra"]
    # showPhoto() wraps the caption in brackets
    return f"({caption})", result


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser(description="Print the setup message the behaviour bench sends")
    ap.add_argument("--woken", action="store_true", help="the woken-by-name variant")
    ap.add_argument("--text", action="store_true", help="only the system instruction, as plain text")
    a = ap.parse_args()
    s = load()
    o = Options(woken_by_name=a.woken)
    if a.text:
        print(instructions(s, o))
    else:
        print(json.dumps(setup_message(s, o), indent=2, ensure_ascii=False))
