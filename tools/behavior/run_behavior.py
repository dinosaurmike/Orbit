#!/usr/bin/env python3
"""Orbit's behaviour bench: runs Orbit's real instructions and tools against the real Gemini
Live API, several times per scenario, and reports how often it behaved.

    export GEMINI_API_KEY=...            # your key (never commit it)
    python3 tools/behavior/run_behavior.py --runs 5 --report behavior-report.md

Exit code: 0 = every scenario at or above its pass threshold, 1 = at least one below,
2 = couldn't run (no key, a key Google refused, no connection, or the app's source changed and
the instructions couldn't be read).
See README.md next to this file.
"""

from __future__ import annotations

import argparse
import asyncio
import contextlib
import hashlib
import os
import re
import sys
import time
import traceback
from dataclasses import dataclass, field, replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Awaitable, Callable, Optional

sys.path.insert(0, str(Path(__file__).resolve().parent))

import checks  # noqa: E402
import media  # noqa: E402
import orbit_prompt  # noqa: E402
from live_client import LiveError, LiveSession  # noqa: E402


# ----------------------------------------------------------------------------------------------
# Timing: how long to wait for things. --quick shrinks it all (for the local mock).
# ----------------------------------------------------------------------------------------------

@dataclass
class Timing:
    turn_timeout: float = 45.0   # longest a reply may take (including a look)
    idle: float = 3.0            # quiet after turnComplete before a reply counts as finished
    listen_after: float = 8.0    # room tone streamed after test audio, while we listen
    pace: float = 1.0            # 1 = audio in real time, like a microphone; 0 = all at once
    camera_delay: float = 0.8    # the app takes a moment to take the photo
    between_runs: float = 2.0    # pause between sessions (be gentle with the free tier)


QUICK = Timing(turn_timeout=5.0, idle=0.3, listen_after=0.5, pace=0.0, camera_delay=0.0, between_runs=0.0)


@dataclass
class Context:
    src: orbit_prompt.OrbitSource
    key: str
    url: str
    timing: Timing
    model: Optional[str] = None      # try a different model than the app's GEMINI_MODEL
    verbose: bool = False
    notes: dict = field(default_factory=dict)  # bench-wide notes for the report
    natural_refused: bool = False
    _media: dict = field(default_factory=dict)

    def setup(self, **opts) -> dict:
        src = replace(self.src, model=self.model) if self.model else self.src
        return orbit_prompt.setup_message(src, orbit_prompt.Options(**opts))

    async def media(self, name: str, make: Callable):
        """A photo or sound, made once per bench run (off the event loop: some take a moment)."""
        if name not in self._media:
            self._media[name] = await asyncio.to_thread(make)
        return self._media[name]

    @contextlib.asynccontextmanager
    async def session(self, **opts):
        """An open conversation. Like the app, if Google refuses the setup before it starts while
        "natural talk" (affective dialog + proactive audio) is on, it tries again without it."""
        natural = not self.natural_refused
        s = LiveSession(self.key, self.setup(natural=natural, **opts), url=self.url)
        try:
            await s.open()
        except LiveError as e:
            await s.close()
            reason = (s.close_reason or "").lower()
            # not a refusal of the setup: no internet, a bad key, the free allowance used up
            if not natural or not reason or any(w in reason for w in ("key", "permission", "quota", "exhausted", "rate")):
                raise
            self.natural_refused = True
            self.notes["Natural talk"] = (f"Google refused it ({e}); ran without it, as the app does "
                                          "when Google refuses it")
            s = LiveSession(self.key, self.setup(natural=False, **opts), url=self.url)
            await s.open()
        try:
            yield s
        finally:
            await s.close()

    def log(self, *a):
        if self.verbose:
            print("   ", *a, flush=True)


@dataclass
class RunResult:
    verdict: checks.Verdict
    said: dict = field(default_factory=dict)        # label → what Orbit said (transcribed)
    heard: str = ""                                  # what Google heard from our audio
    tool_calls: list = field(default_factory=list)
    seconds: float = 0.0
    error: Optional[str] = None


@dataclass
class Scenario:
    name: str
    title: str
    checks: str
    threshold: float
    run: Callable[[Context], Awaitable[RunResult]]


# ----------------------------------------------------------------------------------------------
# Tool answers, as the app gives them
# ----------------------------------------------------------------------------------------------

def look_handler(ctx: Context, jpeg: bytes, calls: list):
    """Answers look like GeminiLive.lookAndAnswer() with no neck (the default): the photo goes
    in first as a clientContent turn, then the tool answer built from PHOTO_RULES."""
    async def handle(session: LiveSession, call: dict):
        calls.append(call)
        if call["name"] != "look":
            await session.silent_ok(call)   # express etc.: "ok", SILENT
            return
        down = (call.get("args") or {}).get("where") == "down"
        caption, result = orbit_prompt.look_caption_and_result(ctx.src, "no_neck" if down else "ahead")
        await asyncio.sleep(ctx.timing.camera_delay)
        await session.show_photo(jpeg, caption)
        await session.tool_response(call, {"result": result})
        ctx.log(f"looked ({'down' if down else 'ahead'}), photo sent")
    return handle


def all_calls_handler(calls: list):
    async def handle(session: LiveSession, call: dict):
        calls.append(call)
        await session.silent_ok(call)
    return handle


# ----------------------------------------------------------------------------------------------
# Scenarios
# ----------------------------------------------------------------------------------------------

async def _desk(ctx: Context, jpeg: bytes, judge) -> RunResult:
    calls: list = []
    async with ctx.session() as s:
        await s.say("What's on my desk?")
        ex = await s.run(look_handler(ctx, jpeg, calls), timeout=ctx.timing.turn_timeout, idle=ctx.timing.idle)
    looks = [c for c in calls if c["name"] == "look"]
    verdict = judge(ex.output_text, looks)
    if ex.timed_out:
        verdict.notes["timed out"] = True
    return RunResult(verdict, {"reply": ex.output_text}, tool_calls=calls)


async def dark_desk(ctx: Context) -> RunResult:
    return await _desk(ctx, await ctx.media("black", media.black_jpeg), checks.judge_dark_desk)


async def real_desk(ctx: Context) -> RunResult:
    return await _desk(ctx, await ctx.media("desk", media.desk_jpeg), checks.judge_real_desk)


async def no_tool_words(ctx: Context) -> RunResult:
    calls: list = []
    t = ctx.timing
    async with ctx.session() as s:
        await s.say("Make a happy face.")
        face = await s.run(all_calls_handler(calls), timeout=t.turn_timeout, idle=t.idle)
        await s.say("What's two plus two?")
        sums = await s.run(all_calls_handler(calls), timeout=t.turn_timeout, idle=t.idle)
    tool_names = [f["name"] for f in orbit_prompt.function_declarations(ctx.src)]
    verdict = checks.judge_no_tool_words(
        [face.output_text, sums.output_text], ctx.src.expressions, tool_names,
        express_called=any(c["name"] == "express" for c in calls), arithmetic_reply=sums.output_text)
    return RunResult(verdict, {"after 'make a happy face'": face.output_text,
                               "after 'two plus two'": sums.output_text}, tool_calls=calls)


async def arrival_quiet(ctx: Context) -> RunResult:
    calls: list = []
    t = ctx.timing
    speech, how = await ctx.media("spanish", media.background_spanish)
    ctx.notes["background speech"] = how
    # the arrival greeting: GREETING sent as a user turn, as GeminiLive.say() does
    async with ctx.session() as s:
        await s.say(ctx.src.constants["GREETING"])
        hello = await s.run(all_calls_handler(calls), timeout=t.turn_timeout, idle=t.idle)
        ctx.log(f"hello: {hello.output_text!r}")
        audio = speech + media.silence(t.listen_after)
        after = await s.run(all_calls_handler(calls), until_turn_complete=False,
                            timeout=media.duration(audio) / (t.pace or 1e9) + t.idle + 1,
                            background=s.send_audio(audio, pace=t.pace, note="background Spanish + room tone"))
    verdict = checks.judge_arrival_quiet(hello.output_text, after.output_text)
    return RunResult(verdict, {"hello": hello.output_text, "after the background talk": after.output_text},
                     heard=after.input_text, tool_calls=calls)


async def unclear_audio(ctx: Context) -> RunResult:
    calls: list = []
    t = ctx.timing
    mumble = await ctx.media("babble", lambda: media.babble(2.0))
    # started by its name (no greeting): sound arrives straight after setup, as the app's
    # buffered early sound does
    async with ctx.session(woken_by_name=True) as s:
        audio = mumble + media.silence(t.listen_after)
        after = await s.run(all_calls_handler(calls), until_turn_complete=False,
                            timeout=media.duration(audio) / (t.pace or 1e9) + t.idle + 1,
                            background=s.send_audio(audio, pace=t.pace, note="mumbling + room tone"))
    verdict = checks.judge_unclear_audio(after.output_text)
    return RunResult(verdict, {"after the mumbling": after.output_text}, heard=after.input_text, tool_calls=calls)


SCENARIOS = [
    Scenario("dark_desk", "Black photo of the desk",
             "Asked \"What's on my desk?\", it must call look, then (shown an all-black photo) name no "
             "desk objects and say it can't see / it's dark / it's not sure.", 1.0, dark_desk),
    Scenario("real_desk", "Drawn desk photo",
             "Shown a sticky note reading ORBIT 42 and a red cube, it must read 42 and name no other "
             "desk objects.", 1.0, real_desk),
    Scenario("no_tool_words", "No tool words out loud",
             "\"Make a happy face.\" then \"What's two plus two?\": it must never say \"happy face\", "
             "\"express\", a tool name, or narrate an expression (nod, wink, *happy*...).", 1.0, no_tool_words),
    Scenario("arrival_quiet", "Quiet after the arrival hello",
             "After the arrival hello, 4 s of someone talking Spanish in the background: it must not "
             "talk about English, languages or speaking (ideally it says nothing).", 1.0, arrival_quiet),
    Scenario("unclear_audio", "Mumbling after its name",
             "Woken by name, then 2 s of mumbling: it must not announce language rules (asking them to "
             "repeat is fine).", 1.0, unclear_audio),
]


# ----------------------------------------------------------------------------------------------
# Running and reporting
# ----------------------------------------------------------------------------------------------

async def run_one(ctx: Context, sc: Scenario) -> RunResult:
    start = time.monotonic()
    try:
        result = await sc.run(ctx)
    except LiveError as e:
        result = RunResult(checks.Verdict(False, [f"error: {e}"]), error=str(e))
    except Exception as e:  # a bug in the bench itself: show it, count the run as failed
        detail = "".join(traceback.format_exception_only(type(e), e)).strip()
        if ctx.verbose:
            traceback.print_exc()
        result = RunResult(checks.Verdict(False, [f"bench error: {detail}"]), error=detail)
    result.seconds = time.monotonic() - start
    return result


async def run_all(ctx: Context, scenarios: list, runs: int) -> dict:
    results = {}
    first = True
    for sc in scenarios:
        results[sc.name] = []
        print(f"{sc.name}: ", end="", flush=True)
        for i in range(runs):
            if not first and ctx.timing.between_runs:
                await asyncio.sleep(ctx.timing.between_runs)
            first = False
            r = await run_one(ctx, sc)
            results[sc.name].append(r)
            print("." if r.verdict.passed else ("E" if r.error else "F"), end="", flush=True)
            if ctx.verbose:
                print(f"\n    run {i + 1}: {'pass' if r.verdict.passed else 'FAIL'} {r.verdict.reasons} {r.said}")
        print()
    return results


def pct(x: float) -> str:
    return f"{round(x * 100)}%"


def summary_rows(scenarios: list, results: dict) -> list:
    rows = []
    for sc in scenarios:
        rs = results[sc.name]
        passed = sum(r.verdict.passed for r in rs)
        rate = passed / len(rs) if rs else 0.0
        rows.append((sc, passed, len(rs), rate, rate >= sc.threshold - 1e-9))
    return rows


def print_table(rows: list):
    print()
    print(f"{'scenario':<16} {'passed':>8} {'rate':>6} {'needs':>6}  result")
    for sc, passed, n, rate, ok in rows:
        print(f"{sc.name:<16} {f'{passed}/{n}':>8} {pct(rate):>6} {pct(sc.threshold):>6}  {'ok' if ok else 'BELOW THRESHOLD'}")
    print()


def md(text: str) -> str:
    """Plain text made safe for a Markdown table (so *happy* isn't shown in italics)."""
    return text.replace("*", "\\*").replace("|", "\\|")


def _quote(text: str) -> str:
    text = (text or "").strip()
    if not text:
        return "*(nothing)*"
    return "“" + md(text).replace("\n", " ") + "”"


def markdown_report(ctx: Context, scenarios: list, results: dict, runs: int) -> str:
    rows = summary_rows(scenarios, results)
    all_ok = all(ok for *_, ok in rows)
    instr = orbit_prompt.instructions(ctx.src, orbit_prompt.Options())
    # a short fingerprint of the wording (without the date line, which changes every run)
    fingerprint = hashlib.sha256(re.sub(r"It is currently [^\n]*", "", instr).encode()).hexdigest()[:12]
    lines = [
        "# Orbit behaviour bench",
        "",
        f"**{'All scenarios passed' if all_ok else 'Some scenarios fell below their pass mark'}** · "
        f"model `{ctx.model or ctx.src.model}` · {runs} run{'s' if runs != 1 else ''} each · "
        f"{datetime.now(timezone.utc):%Y-%m-%d %H:%M} UTC",
        "",
        "| Scenario | What it checks | Passed | Rate | Needs | |",
        "|---|---|---|---|---|---|",
    ]
    for sc, passed, n, rate, ok in rows:
        lines.append(f"| `{sc.name}` | {md(sc.checks)} | {passed}/{n} | {pct(rate)} | {pct(sc.threshold)} | "
                     f"{'✅' if ok else '❌'} |")
    extra = {
        "Instructions": f"the app's real text, {len(instr)} characters, fingerprint "
                        f"`{fingerprint}` "
                        "(changes whenever the wording does)",
        "Voice": ctx.src.voice,
        **ctx.notes,
    }
    lines += ["", *[f"- **{k}:** {v}" for k, v in extra.items()], ""]
    for sc, passed, n, rate, ok in rows:
        lines += [f"## {sc.title} (`{sc.name}`): {passed}/{n}", "", md(sc.checks), ""]
        for i, r in enumerate(results[sc.name], 1):
            mark = "pass" if r.verdict.passed else ("error" if r.error else "FAIL")
            lines.append(f"**Run {i}: {mark}** ({r.seconds:.0f} s)")
            lines.append("")
            for reason in r.verdict.reasons:
                lines.append(f"- ✗ {reason}")
            for label, text in r.said.items():
                lines.append(f"- Orbit said ({label}): {_quote(text)}")
            if r.heard.strip():
                lines.append(f"- Google heard from our audio: {_quote(r.heard)}")
            if r.tool_calls:
                calls = ", ".join(f"{c['name']}({', '.join(f'{k}={v}' for k, v in (c.get('args') or {}).items())})"
                                  for c in r.tool_calls)
                lines.append(f"- Tool calls: {calls}")
            for k, v in r.verdict.notes.items():
                lines.append(f"- {k}: {'yes' if v is True else 'no' if v is False else v}")
            lines.append("")
    return "\n".join(lines)


def parse_thresholds(items: list, scenarios: list) -> None:
    names = {sc.name: sc for sc in scenarios}
    for item in items or []:
        name, _, value = item.partition("=")
        if name not in names or not value:
            raise SystemExit(f"--threshold wants name=fraction, with name one of {', '.join(names)} (got {item!r})")
        try:
            v = float(value.rstrip("%"))
        except ValueError:
            raise SystemExit(f"--threshold {item!r}: {value!r} isn't a number")
        if value.endswith("%") or v > 1:
            v /= 100  # 80% or 80 both mean 0.8
        names[name].threshold = v


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Run Orbit's behaviour scenarios against Gemini Live.")
    ap.add_argument("--runs", type=int, default=3, help="times to run each scenario (default 3)")
    ap.add_argument("--only", help="comma-separated scenario names (default: all)")
    ap.add_argument("--report", help="write a Markdown report to this file")
    ap.add_argument("--threshold", action="append", metavar="NAME=RATE",
                    help="pass mark for a scenario, e.g. arrival_quiet=0.8 or arrival_quiet=80%% (repeatable)")
    ap.add_argument("--model", help="try another Live model instead of the app's GEMINI_MODEL")
    ap.add_argument("--url", default=os.environ.get("ORBIT_LIVE_URL", orbit_prompt.LIVE_URL),
                    help="Live API address (default: Google's; a ws://localhost address for the mock)")
    ap.add_argument("--quick", action="store_true", help="short waits and no real-time pacing (for the mock)")
    ap.add_argument("--list", action="store_true", help="list the scenarios and exit")
    ap.add_argument("-v", "--verbose", action="store_true", help="print each run's words as it goes")
    args = ap.parse_args(argv)

    scenarios = [Scenario(**{**sc.__dict__}) for sc in SCENARIOS]  # copies: thresholds may change
    if args.list:
        for sc in scenarios:
            print(f"{sc.name:<16} needs {pct(sc.threshold):>4}  {sc.checks}")
        return 0
    if args.only:
        wanted = [w.strip() for w in args.only.split(",") if w.strip()]
        unknown = [w for w in wanted if w not in {sc.name for sc in scenarios}]
        if unknown:
            print(f"Unknown scenario(s): {', '.join(unknown)}. Try --list.", file=sys.stderr)
            return 2
        scenarios = [sc for sc in scenarios if sc.name in wanted]
    parse_thresholds(args.threshold, scenarios)
    if args.runs < 1:
        print("--runs must be at least 1", file=sys.stderr)
        return 2

    key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not key:
        if args.url.startswith("ws://"):
            key = "mock-key"
        else:
            print("No GEMINI_API_KEY in the environment. Set it to your Gemini API key and try again.",
                  file=sys.stderr)
            return 2
    try:
        src = orbit_prompt.load()
    except orbit_prompt.ExtractionError as e:
        print(f"Couldn't read Orbit's instructions from the app's source: {e}", file=sys.stderr)
        return 2

    ctx = Context(src, key, args.url, QUICK if args.quick else Timing(), model=args.model, verbose=args.verbose)
    print(f"Orbit behaviour bench: model {args.model or src.model}, {args.runs} run(s) per scenario")
    results = asyncio.run(run_all(ctx, scenarios, args.runs))
    rows = summary_rows(scenarios, results)
    print_table(rows)
    if args.report:
        Path(args.report).write_text(markdown_report(ctx, scenarios, results, args.runs), encoding="utf-8")
        print(f"Report written to {args.report}")
    every = [r for rs in results.values() for r in rs]
    if every and all(r.error for r in every):
        print(f"Every run failed before Orbit could be tested. First error: {every[0].error}", file=sys.stderr)
        return 2
    return 0 if all(ok for *_, ok in rows) else 1


if __name__ == "__main__":
    sys.exit(main())
