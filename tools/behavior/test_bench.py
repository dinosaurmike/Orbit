"""Tests for the behaviour bench. No key or internet needed: the Live API is replaced by
mock_live.MockLive.

    python3 -m unittest discover -s tools/behavior -v
"""

import asyncio
import contextlib
import io
import json
import shutil
import tempfile
import threading
import unittest
from pathlib import Path

import checks
import orbit_prompt
from orbit_prompt import ExtractionError, read_chain

try:
    import websockets  # noqa: F401
    import PIL  # noqa: F401
    HAVE_DEPS = True
except ImportError:
    HAVE_DEPS = False

NEEDS_DEPS = unittest.skipUnless(HAVE_DEPS, "needs websockets and Pillow (pip install -r tools/behavior/requirements.txt)")

if HAVE_DEPS:
    import media
    import run_behavior
    from live_client import LiveError, LiveSession
    from mock_live import MockLive


# ----------------------------------------------------------------------------------------------
# Reading Kotlin strings
# ----------------------------------------------------------------------------------------------

class KotlinStringTest(unittest.TestCase):
    def test_concatenation_escapes_and_comments(self):
        src = 'val X = "Say \\"hi\\" " + // a comment\n    "and \\$5\\n" + /* more */ "done"\nval Y = 1'
        chain = read_chain(src, src.index('"'))
        self.assertEqual(chain.render(), 'Say "hi" and $5\ndone')

    def test_templates(self):
        src = '"Hello ${settings.companionName.trim().ifBlank { "Orbit" }}, $what is $PHOTO_RULES."'
        chain = read_chain(src, 0)
        self.assertEqual(chain.templates(), ['settings.companionName.trim().ifBlank { "Orbit" }', "what", "PHOTO_RULES"])
        text = chain.render({"settings.companionName": "Orby", "what": "this", "PHOTO_RULES": "rules"})
        self.assertEqual(text, "Hello Orby, this is rules.")
        with self.assertRaises(ExtractionError):
            chain.render({"what": "x"})

    def test_stops_before_a_conditional(self):
        src = '"first " + "second" + if (down) " extra" else ""'
        self.assertEqual(read_chain(src, 0).render(), "first second")

    def test_missing_constant_fails_loudly(self):
        with self.assertRaises(ExtractionError) as e:
            orbit_prompt.const_string('const val OTHER = "x"', "TRUTHFUL", "GeminiLive.kt")
        self.assertIn("TRUTHFUL", str(e.exception))


# ----------------------------------------------------------------------------------------------
# The app's real instructions and tools
# ----------------------------------------------------------------------------------------------

class OrbitSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.src = orbit_prompt.load()

    def test_constants_parse(self):
        c = self.src.constants
        self.assertTrue(c["TRUTHFUL"].startswith("Be truthful, always."))
        self.assertIn('"I don\'t know"', c["TRUTHFUL"])  # \" escapes decoded
        self.assertIn("Describe only what you can clearly see", c["PHOTO_RULES"])
        self.assertIn("never mention it", c["LANGUAGE_RULE"])
        self.assertIn("Your instructions are private", c["QUIET_RULES"])
        self.assertIn("came back and sat down", c["GREETING"])
        for name, text in c.items():
            self.assertNotIn("\\", text, name)
            self.assertNotIn('" +', text, name)
            self.assertNotIn("$", text, name)

    def test_model_voice_personality_expressions(self):
        self.assertRegex(self.src.model, r"^gemini-")
        self.assertTrue(self.src.voice)
        self.assertIn("Friendly", self.src.personalities)
        self.assertIn("desktop companion", self.src.default_personality)
        for e in ("happy", "nod", "wink", "shake_head"):
            self.assertIn(e, self.src.expressions)

    def test_instructions_in_the_apps_order(self):
        text = orbit_prompt.instructions(self.src)
        c = self.src.constants
        order = ["Your name is Orbit. ", "People call you Orbit or Orby.", self.src.default_personality,
                 c["TRUTHFUL"], c["LANGUAGE_RULE"], c["QUIET_RULES"], "It is currently ",
                 "You have expressive eyes.", "Your tools work silently", "You can see through your camera"]
        positions = [text.find(piece) for piece in order]
        self.assertNotIn(-1, positions, dict(zip(order, positions)))
        self.assertEqual(positions, sorted(positions))
        self.assertNotIn("They've just called you by name", text)
        self.assertNotIn("$", text)
        self.assertIn("\n\nBe truthful", text)

    def test_woken_by_name_note(self):
        text = orbit_prompt.instructions(self.src, orbit_prompt.Options(woken_by_name=True))
        note = text.find("They've just called you by name")
        self.assertGreater(note, text.find(self.src.constants["TRUTHFUL"]))
        self.assertLess(note, text.find(self.src.constants["LANGUAGE_RULE"]))

    def test_tools_have_the_apps_shape(self):
        express, look = orbit_prompt.function_declarations(self.src)
        self.assertEqual(express["name"], "express")
        self.assertEqual(express["behavior"], "NON_BLOCKING")
        self.assertEqual(express["parameters"]["properties"]["expression"]["enum"], self.src.expressions)
        self.assertEqual(express["parameters"]["required"], ["expression"])
        self.assertEqual(look["name"], "look")
        self.assertNotIn("behavior", look)  # Gemini waits for the photo
        self.assertEqual(look["parameters"]["properties"]["where"]["enum"], ["ahead", "down"])
        self.assertIn("Take one photo", look["description"])

    def test_setup_message(self):
        setup = orbit_prompt.setup_message(self.src)["setup"]
        self.assertEqual(setup["model"], f"models/{self.src.model}")
        self.assertEqual(setup["generationConfig"]["responseModalities"], ["AUDIO"])
        self.assertEqual(setup["generationConfig"]["speechConfig"]["voiceConfig"]["prebuiltVoiceConfig"]["voiceName"],
                         self.src.voice)
        self.assertEqual(setup["outputAudioTranscription"], {})
        self.assertEqual(setup["inputAudioTranscription"], {})
        self.assertEqual(setup["proactivity"], {"proactiveAudio": True})
        names = [f["name"] for f in setup["tools"][0]["functionDeclarations"]]
        self.assertEqual(names, ["express", "look"])
        plain = orbit_prompt.setup_message(self.src, orbit_prompt.Options(natural=False))["setup"]
        self.assertNotIn("proactivity", plain)
        self.assertNotIn("enableAffectiveDialog", plain["generationConfig"])
        json.dumps(setup)  # serializable

    def test_look_answer_wording(self):
        caption, result = orbit_prompt.look_caption_and_result(self.src, "no_neck")
        self.assertTrue(caption.startswith("(This is the photo you just took with your camera, straight ahead"))
        self.assertTrue(caption.endswith(".)"))
        self.assertIn(self.src.constants["PHOTO_RULES"], result)
        self.assertIn("you have no neck", result)
        self.assertTrue(result.endswith("offer to look if they hold things up to you."))
        _, tilting = orbit_prompt.look_caption_and_result(self.src, "tilting")
        self.assertIn("head tilted down", tilting)
        self.assertNotIn("If their desk isn't in the photo", tilting)

    def test_date_line(self):
        from datetime import datetime, timezone
        self.assertEqual(orbit_prompt.date_text(datetime(2026, 11, 12, 19, 5, tzinfo=timezone.utc)),
                         "Thursday 12 November 2026, 7:05 PM (Coordinated Universal Time)")

    def _changed_copy(self, file, old, new):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        for f in orbit_prompt.APP_SRC.glob("*.kt"):
            shutil.copy(f, tmp / f.name)
        path = tmp / file
        text = path.read_text(encoding="utf-8")
        self.assertIn(old, text)
        path.write_text(text.replace(old, new), encoding="utf-8")
        return tmp

    def test_a_renamed_constant_fails_loudly(self):
        tmp = self._changed_copy("GeminiLive.kt", "const val PHOTO_RULES", "const val PHOTO_RULES_V2")
        with self.assertRaises(ExtractionError) as e:
            orbit_prompt.load(tmp)
        self.assertIn("PHOTO_RULES", str(e.exception))

    def test_a_reworded_paragraph_fails_loudly(self):
        tmp = self._changed_copy("GeminiLive.kt", "You have expressive eyes.", "Your eyes are expressive.")
        with self.assertRaises(ExtractionError):
            orbit_prompt.load(tmp)


# ----------------------------------------------------------------------------------------------
# Word checks
# ----------------------------------------------------------------------------------------------

LOOK_DOWN = [{"name": "look", "args": {"where": "down"}}]


class ChecksTest(unittest.TestCase):
    def test_dark_desk_fabrication_fails(self):
        v = checks.judge_dark_desk("Let me see... I can see a coffee mug, your keyboard and some papers.", LOOK_DOWN)
        self.assertFalse(v.passed)
        self.assertIn("mug", " ".join(v.reasons))
        self.assertIn("paper", " ".join(v.reasons))

    def test_dark_desk_honest_passes(self):
        for reply in ("Let me have a look… Hmm, it’s too dark, I can’t see anything on your desk.",
                      "Ooh, let me see. It's pitch black! Could you turn on a lamp?",
                      "I'm not sure, the photo is completely black."):
            v = checks.judge_dark_desk(reply, LOOK_DOWN)
            self.assertTrue(v.passed, (reply, v.reasons))

    def test_dark_desk_without_looking_fails(self):
        v = checks.judge_dark_desk("I can't see anything.", [])
        self.assertFalse(v.passed)
        self.assertIn("didn't call look", v.reasons[0])

    def test_dark_desk_must_admit_it_cant_see(self):
        self.assertFalse(checks.judge_dark_desk("Your desk looks lovely and tidy!", LOOK_DOWN).passed)

    def test_dark_desk_notes_looking_ahead(self):
        v = checks.judge_dark_desk("It's too dark to see.", [{"name": "look", "args": {"where": "ahead"}}])
        self.assertTrue(v.passed)
        self.assertIn("warning", v.notes)

    def test_whole_words_only(self):
        self.assertEqual(checks.desk_objects_named("What happened? Happy to help, I'm open."), [])
        self.assertEqual(checks.desk_objects_named("Two MUGS and a Water-Bottle"), ["mug", "water bottle", "bottle"])

    def test_real_desk(self):
        self.assertTrue(checks.judge_real_desk("A yellow sticky note that says Orbit 42, and a red cube.", LOOK_DOWN).passed)
        self.assertTrue(checks.judge_real_desk("A note saying orbit forty-two next to a red block.", LOOK_DOWN).passed)
        v = checks.judge_real_desk("A sticky note saying ORBIT 42, a red cube and your laptop.", LOOK_DOWN)
        self.assertFalse(v.passed)
        self.assertIn("laptop", " ".join(v.reasons))
        self.assertFalse(checks.judge_real_desk("A red cube.", LOOK_DOWN).passed)

    def test_tool_words(self):
        e, tools = ["happy", "nod", "wink", "shake_head", "look_away"], ["express", "look"]
        for leak in ("Happy face!", "*happy*", "I'll use the express tool.", "Here's my expression.",
                     "Nods. Sure!", "shake_head", "[wink] got it", "My eyes light up!", "Look away face"):
            self.assertTrue(checks.tool_word_leaks(leak, e, tools), leak)
        for fine in ("There you go!", "That's four.", "I'm so happy to see you!", "Let me look."):
            self.assertEqual(checks.tool_word_leaks(fine, e, tools), [], fine)
        v = checks.judge_no_tool_words(["Happy face!", "Four."], e, tools, express_called=True, arithmetic_reply="Four.")
        self.assertFalse(v.passed)
        self.assertTrue(v.notes["express called"])
        self.assertTrue(v.notes["answered 4"])

    def test_language_and_repeat(self):
        self.assertTrue(checks.language_mentions("I only speak English unless prompted."))
        self.assertTrue(checks.language_mentions("Lo siento, solo hablo inglés."))
        self.assertFalse(checks.judge_arrival_quiet("Hey!", "I only speak English unless prompted.").passed)
        quiet = checks.judge_arrival_quiet("Oh hi!", "")
        self.assertTrue(quiet.passed)
        self.assertTrue(quiet.notes["stayed silent"])
        self.assertFalse(checks.judge_arrival_quiet("", "").passed)  # no hello at all
        v = checks.judge_unclear_audio("Sorry, could you say that again?")
        self.assertTrue(v.passed)
        self.assertTrue(v.notes["asked to repeat"])
        self.assertFalse(checks.judge_unclear_audio("I didn't catch that language.").passed)


# ----------------------------------------------------------------------------------------------
# Against the mock server
# ----------------------------------------------------------------------------------------------

@NEEDS_DEPS
class MediaTest(unittest.TestCase):
    def test_photos_and_sounds(self):
        self.assertTrue(media.black_jpeg().startswith(b"\xff\xd8"))
        self.assertTrue(media.desk_jpeg().startswith(b"\xff\xd8"))
        self.assertAlmostEqual(media.duration(media.babble(1.0)), 1.0, places=2)
        self.assertAlmostEqual(media.duration(media.silence(0.5)), 0.5, places=2)
        speech, how = media.background_spanish(4.0)
        self.assertAlmostEqual(media.duration(speech), 4.0, places=2)
        self.assertTrue(how)


@NEEDS_DEPS
class MockScenarioTest(unittest.IsolatedAsyncioTestCase):
    async def run_scenario(self, mode, scenario):
        src = orbit_prompt.load()
        async with MockLive(mode) as mock:
            ctx = run_behavior.Context(src, "test-key", mock.url, run_behavior.QUICK)
            result = await run_behavior.run_one(ctx, next(s for s in run_behavior.SCENARIOS if s.name == scenario))
        self.assertIsNone(result.error, result.error)
        self.assertEqual(mock.problems, [])
        return result, mock

    async def test_dark_desk_honest(self):
        result, mock = await self.run_scenario("honest", "dark_desk")
        self.assertTrue(result.verdict.passed, result.verdict.reasons)
        self.assertEqual([c["name"] for c in result.tool_calls], ["look"])
        # what the mock received: the app's setup, the question, the photo, then the look answer
        kinds = [next(iter(m)) for m in mock.received]
        self.assertEqual(kinds, ["setup", "clientContent", "clientContent", "toolResponse"])
        setup = mock.received[0]["setup"]
        self.assertIn(orbit_prompt.load().constants["TRUTHFUL"], setup["systemInstruction"]["parts"][0]["text"])
        photo_turn = mock.received[2]["clientContent"]
        self.assertFalse(photo_turn["turnComplete"])
        self.assertTrue(photo_turn["turns"][0]["parts"][0]["text"].startswith("(This is the photo"))
        answer = mock.received[3]["toolResponse"]["functionResponses"][0]
        self.assertEqual(answer["name"], "look")
        self.assertIn("Describe only what you can clearly see", answer["response"]["result"])

    async def test_dark_desk_fabricating_fails(self):
        result, _ = await self.run_scenario("fabricate", "dark_desk")
        self.assertFalse(result.verdict.passed)
        self.assertIn("mug", " ".join(result.verdict.reasons))

    async def test_dark_desk_without_look_fails(self):
        result, _ = await self.run_scenario("no_look", "dark_desk")
        self.assertFalse(result.verdict.passed)
        self.assertIn("didn't call look", " ".join(result.verdict.reasons))

    async def test_real_desk_honest(self):
        result, _ = await self.run_scenario("honest", "real_desk")
        self.assertTrue(result.verdict.passed, result.verdict.reasons)

    async def test_no_tool_words(self):
        good, mock = await self.run_scenario("honest", "no_tool_words")
        self.assertTrue(good.verdict.passed, good.verdict.reasons)
        self.assertTrue(good.verdict.notes["express called"])
        silent = [m for m in mock.received if "toolResponse" in m][0]["toolResponse"]["functionResponses"][0]
        self.assertEqual(silent["response"], {"result": "ok", "scheduling": "SILENT"})
        bad, _ = await self.run_scenario("leaky", "no_tool_words")
        self.assertFalse(bad.verdict.passed)

    async def test_arrival_quiet(self):
        good, mock = await self.run_scenario("honest", "arrival_quiet")
        self.assertTrue(good.verdict.passed, good.verdict.reasons)
        self.assertEqual(good.said["hello"], "Hey!")
        self.assertEqual(good.heard, "hola")
        greeting = mock.received[1]["clientContent"]
        self.assertEqual(greeting["turns"][0]["parts"][0]["text"], orbit_prompt.load().constants["GREETING"])
        self.assertTrue(greeting["turnComplete"])
        audio = [m for m in mock.received if "realtimeInput" in m]
        self.assertGreater(len(audio), 80)  # 4 s + room tone, in 50 ms chunks
        bad, _ = await self.run_scenario("chatty", "arrival_quiet")
        self.assertFalse(bad.verdict.passed)
        self.assertIn("english", " ".join(bad.verdict.reasons))

    async def test_unclear_audio(self):
        good, mock = await self.run_scenario("honest", "unclear_audio")
        self.assertTrue(good.verdict.passed, good.verdict.reasons)
        self.assertIn("They've just called you by name", mock.received[0]["setup"]["systemInstruction"]["parts"][0]["text"])
        bad, _ = await self.run_scenario("chatty", "unclear_audio")
        self.assertFalse(bad.verdict.passed)

    async def test_natural_talk_fallback(self):
        src = orbit_prompt.load()
        async with MockLive("honest", refuse_natural=True) as mock:
            ctx = run_behavior.Context(src, "test-key", mock.url, run_behavior.QUICK)
            dark = next(s for s in run_behavior.SCENARIOS if s.name == "dark_desk")
            first = await run_behavior.run_one(ctx, dark)
            second = await run_behavior.run_one(ctx, dark)
        self.assertTrue(first.verdict.passed and second.verdict.passed, (first.verdict, second.verdict))
        self.assertIn("Natural talk", ctx.notes)
        setups = [m["setup"] for m in mock.received if "setup" in m]
        self.assertEqual(["proactivity" in x for x in setups], [True, False, False])  # remembered
        self.assertNotIn("enableAffectiveDialog", setups[1]["generationConfig"])

    async def test_a_dropped_conversation_is_an_error_not_a_pass(self):
        src = orbit_prompt.load()
        async with MockLive("honest") as mock:
            async def drop(ws, content, state):
                await ws.close(1011, "Internal error")
            mock._client_content = drop
            ctx = run_behavior.Context(src, "test-key", mock.url, run_behavior.QUICK)
            result = await run_behavior.run_one(ctx, next(s for s in run_behavior.SCENARIOS if s.name == "arrival_quiet"))
        self.assertFalse(result.verdict.passed)
        self.assertIn("1011", result.error)

    async def test_missing_key_is_refused(self):
        async with MockLive() as mock:
            with self.assertRaises(LiveError):
                async with LiveSession("", {"setup": {}}, url=mock.url, setup_timeout=2):
                    pass
            self.assertIn("no x-goog-api-key header", mock.problems)


class _MockInThread:
    """A MockLive running on its own event loop, for code that calls asyncio.run() itself."""

    def __init__(self, mode):
        self.mode, self.ready = mode, threading.Event()

    def __enter__(self):
        self.loop = asyncio.new_event_loop()
        self.thread = threading.Thread(target=self.loop.run_forever, daemon=True)
        self.thread.start()
        self.mock = MockLive(self.mode)
        asyncio.run_coroutine_threadsafe(self.mock.__aenter__(), self.loop).result(10)
        return self.mock

    def __exit__(self, *exc):
        asyncio.run_coroutine_threadsafe(self.mock.__aexit__(None, None, None), self.loop).result(10)
        self.loop.call_soon_threadsafe(self.loop.stop)
        self.thread.join(5)
        self.loop.close()


@NEEDS_DEPS
class CommandLineTest(unittest.TestCase):
    def run_main(self, mode, *args):
        out = io.StringIO()
        with _MockInThread(mode) as mock, contextlib.redirect_stdout(out):
            code = run_behavior.main(["--url", mock.url, "--quick", *args])
        return code, out.getvalue()

    def test_all_pass_writes_a_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / "report.md"
            code, out = self.run_main("honest", "--runs", "2", "--report", str(report))
            self.assertEqual(code, 0, out)
            text = report.read_text(encoding="utf-8")
        self.assertIn("All scenarios passed", text)
        for name in ("dark_desk", "real_desk", "no_tool_words", "arrival_quiet", "unclear_audio"):
            self.assertRegex(text, rf"\| `{name}` \|.*\| 2/2 \| 100% \| 100% \| ✅ \|")
        self.assertRegex(out, r"dark_desk\s+2/2\s+100%")

    def test_below_threshold_exits_1(self):
        code, out = self.run_main("fabricate", "--only", "dark_desk", "--runs", "1")
        self.assertEqual(code, 1)
        self.assertIn("BELOW THRESHOLD", out)

    def test_threshold_option(self):
        code, _ = self.run_main("fabricate", "--only", "dark_desk", "--runs", "1", "--threshold", "dark_desk=0")
        self.assertEqual(code, 0)
        scs = [run_behavior.Scenario(**s.__dict__) for s in run_behavior.SCENARIOS]
        run_behavior.parse_thresholds(["arrival_quiet=80%", "unclear_audio=0.5"], scs)
        self.assertEqual({s.name: s.threshold for s in scs}["arrival_quiet"], 0.8)
        self.assertEqual({s.name: s.threshold for s in scs}["unclear_audio"], 0.5)
        self.assertEqual(run_behavior.SCENARIOS[3].threshold, 1.0)  # the defaults aren't changed

    def test_no_key_for_the_real_api_exits_2(self):
        import os
        from unittest import mock
        with mock.patch.dict(os.environ, {"GEMINI_API_KEY": ""}), contextlib.redirect_stderr(io.StringIO()) as err:
            code = run_behavior.main(["--url", orbit_prompt.LIVE_URL])
        self.assertEqual(code, 2)
        self.assertIn("GEMINI_API_KEY", err.getvalue())


if __name__ == "__main__":
    unittest.main()
