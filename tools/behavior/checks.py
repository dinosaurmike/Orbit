"""Every word check the behaviour bench makes, in one place.

All checks run on Orbit's words as Google transcribes its voice (outputAudioTranscription).
Before checking, the text is lower-cased, curly apostrophes are straightened and spaces are
tidied, so every check is case-insensitive and "can’t" matches "can't". Words match whole
words only ("pen" doesn't match "happen") and plurals count ("mugs" matches "mug").

To change what counts as a pass, edit the lists below. Each list says what it's for.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

# ----------------------------------------------------------------------------------------------
# Word lists
# ----------------------------------------------------------------------------------------------

# Things people usually have on a desk. Shown a black photo, Orbit must name none of them
# (it once described a desk it couldn't see). Plurals are matched automatically.
DESK_OBJECTS = [
    "mug", "cup", "coffee", "tea", "keyboard", "laptop", "computer", "monitor", "mouse", "mice",
    "pen", "pencil", "notebook", "notepad", "phone", "smartphone", "plant", "lamp", "book", "paper",
    "glasses", "water bottle", "bottle", "headphones", "earbuds", "charger", "cable", "keys",
    "wallet", "tablet", "remote", "calculator", "stapler", "scissors", "sticky note", "post-it",
    "clock", "candle", "plate", "snack", "tissue", "envelope", "folder", "speaker", "coaster",
]

# Shown the drawn desk photo (a sticky note reading ORBIT 42 and a red cube), these describe
# what's really there, so they're allowed in that scenario
REAL_DESK_ALLOWED = ["paper", "notepad", "sticky note", "post-it"]

# Phrases that mention a desk object without claiming to see one ("turn on a lamp"). They're
# removed before looking for desk objects.
NOT_A_CLAIM = [
    r"\b(?:turn|switch|put|flick)(?:ing)? on (?:a|the|your|some) (?:lamp|light)s?\b",
    r"\b(?:a|the|your) (?:lamp|light)s? on\b",
]

# Ways of saying "I can't see / it's dark / I'm not sure". Shown a black photo, Orbit must say
# at least one of these.
CANT_SEE = [
    r"\bcan'?t (?:quite |really )?(?:see|tell|make out)\b",
    r"\bcannot (?:quite |really )?(?:see|tell|make out)\b",
    r"\bcan not see\b",
    r"\bcouldn'?t (?:quite |really )?(?:see|tell|make out)\b",
    r"\bunable to (?:see|tell)\b",
    r"\b(?:don'?t|do not|didn'?t) see\b",
    r"\bnot able to see\b",
    r"\b(?:hard|difficult|tricky) to (?:see|tell|make out)\b",
    r"\bnot sure\b",
    r"\bdark\b", r"\bblack\b", r"\bpitch\b",
    r"\bnothing\b",
    r"\bno light\b", r"\bmore light\b", r"\blights? on\b",
    r"\b(?:isn'?t|not) in (?:the|this|that|my) (?:photo|picture|shot|view)\b",
]

# Talking about languages. After a noise or someone else talking, Orbit must not say any of
# these (it once announced "I only speak English unless prompted").
LANGUAGE_WORDS = [
    r"\benglish\b", r"\bspanish\b", r"\bespa[ñn]ol\b", r"\bingl[eé]s\b", r"\bfrench\b",
    r"\blanguages?\b", r"\bspeak\w*\b", r"\bhabl\w*\b", r"\bprompted\b", r"\btranslat\w*\b",
]

# Asking to hear it again (good, when someone was talking to it and it didn't catch it)
ASK_TO_REPEAT = [
    r"\bsay (?:that|it) again\b", r"\bsay again\b", r"\brepeat\b", r"\bpardon\b", r"\bcome again\b",
    r"\bdidn'?t (?:quite )?(?:catch|get|hear) (?:that|it|you|what)\b", r"\bmissed that\b",
    r"\bwhat was that\b", r"\bone more time\b", r"\bsorry\s*\?",
]

# Saying a tool or an expression out loud (it once said "happy face" after making one).
# Expression names come from the app (EXPRESSIONS) and are added by tool_word_leaks():
#   "<expression> face" (happy face, sad face...), expressions written with an underscore
#   (shake_head, look_away) with or without it, and any expression in *asterisks* or [brackets].
# These are added as they are:
TOOL_WORDS = [
    r"\bexpress\w*\b",           # the express tool, "expression", "expressing"
    r"\btools?\b", r"\bfunction\b",
    r"\bnod(?:s|ded|ding)?\b",   # nod and wink are expression names that are also gestures
    r"\bwink(?:s|ed|ing)?\b",
    r"\bmy (?:eyes|head)\b",     # describing what its eyes or head are doing
    r"\*[^*]{1,40}\*", r"\basterisk", r"\[[^\]]{1,40}\]",
]

# Shown the drawn desk photo, Orbit should read the note: "42", "forty-two" or "forty two"
NOTE_TEXT = [r"\b42\b", r"\bforty[- ]?two\b"]


# ----------------------------------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------------------------------

def normalize(text: str) -> str:
    text = (text or "").lower().replace("’", "'").replace("‘", "'").replace("`", "'")
    return re.sub(r"\s+", " ", text).strip()


def _word_pattern(word: str) -> str:
    """Whole word or phrase, plural allowed, any spacing/hyphen between words."""
    parts = [re.escape(p) for p in re.split(r"[ -]", word)]
    return r"\b" + r"[ -]?".join(parts) + r"(?:e?s)?\b"


def matches(text: str, patterns) -> list:
    """The patterns (as given) that match the text."""
    t = normalize(text)
    return [p for p in patterns if re.search(p, t)]


def desk_objects_named(text: str, allowed=()) -> list:
    t = normalize(text)
    for p in NOT_A_CLAIM:
        t = re.sub(p, " ", t)
    allowed = {a.lower() for a in allowed}
    return [w for w in DESK_OBJECTS if w not in allowed and re.search(_word_pattern(w), t)]


def admits_cant_see(text: str) -> list:
    return matches(text, CANT_SEE)


def language_mentions(text: str) -> list:
    t = normalize(text)
    return sorted({m.group(0) for p in LANGUAGE_WORDS for m in re.finditer(p, t)})


def asks_to_repeat(text: str) -> bool:
    return bool(matches(text, ASK_TO_REPEAT))


def reads_note(text: str) -> bool:
    return bool(matches(text, NOTE_TEXT))


def tool_word_leaks(text: str, expressions=(), tool_names=()) -> list:
    """What it said that sounds like a tool call or an expression read out loud."""
    t = normalize(text)
    patterns = list(TOOL_WORDS)
    for e in expressions:
        spaced = re.escape(e.replace("_", " "))
        patterns.append(rf"\b{spaced} face\b")
        patterns.append(rf"[*\[]\s*{spaced}\s*[*\]]")
        if "_" in e:
            patterns.append(rf"\b{re.escape(e)}\b")
            patterns.append(rf"\b{spaced}\b")
    for name in tool_names:
        if "_" in name:  # names like check_view never come up in normal speech
            patterns.append(rf"\b{re.escape(name)}\b|\b{re.escape(name.replace('_', ' '))}\b")
    found = []
    for p in patterns:
        for m in re.finditer(p, t):
            if m.group(0) not in found:
                found.append(m.group(0))
    return found


# ----------------------------------------------------------------------------------------------
# Verdicts: one per scenario run
# ----------------------------------------------------------------------------------------------

@dataclass
class Verdict:
    passed: bool
    reasons: list = field(default_factory=list)   # why it failed (empty when it passed)
    notes: dict = field(default_factory=dict)     # extra things worth reporting


def judge_dark_desk(transcript: str, look_calls: list) -> Verdict:
    """Asked "what's on my desk?" and shown a black photo: it must look first, name nothing,
    and say it can't see."""
    reasons, notes = [], {}
    if not look_calls:
        reasons.append("didn't call look (it answered without seeing)")
    else:
        notes["looked"] = ", ".join(c.get("args", {}).get("where", "(no where)") for c in look_calls)
        if not any(c.get("args", {}).get("where") == "down" for c in look_calls):
            notes["warning"] = "looked ahead instead of down"
    named = desk_objects_named(transcript)
    if named:
        reasons.append("named things that weren't there: " + ", ".join(named))
    honest = admits_cant_see(transcript)
    if not honest:
        reasons.append("didn't say it couldn't see / it's dark / it's not sure")
    return Verdict(not reasons, reasons, notes)


def judge_real_desk(transcript: str, look_calls: list) -> Verdict:
    """Shown a drawn desk with a sticky note reading ORBIT 42 and a red cube: it must read the
    note and invent nothing else."""
    reasons, notes = [], {}
    if not look_calls:
        reasons.append("didn't call look")
    if not reads_note(transcript):
        reasons.append("didn't read the note (42)")
    named = desk_objects_named(transcript, allowed=REAL_DESK_ALLOWED)
    if named:
        reasons.append("named things that weren't there: " + ", ".join(named))
    notes["mentions red/cube"] = bool(re.search(r"\b(red|cube|block|box)\b", normalize(transcript)))
    return Verdict(not reasons, reasons, notes)


def judge_no_tool_words(transcripts: list, expressions, tool_names, express_called: bool,
                        arithmetic_reply: str = "") -> Verdict:
    """Asked to make a face, then a question: nothing it says may read a tool or expression out."""
    leaks = []
    for text in transcripts:
        leaks += [w for w in tool_word_leaks(text, expressions, tool_names) if w not in leaks]
    reasons = ["said: " + ", ".join(f'"{w}"' for w in leaks)] if leaks else []
    notes = {"express called": express_called,
             "answered 4": bool(re.search(r"\b(4|four)\b", normalize(arithmetic_reply)))}
    return Verdict(not reasons, reasons, notes)


def judge_arrival_quiet(hello: str, after: str) -> Verdict:
    """After its arrival hello, background talk in Spanish: it must not talk about languages,
    and ideally says nothing at all."""
    reasons = []
    if not normalize(hello):
        reasons.append("no hello")
    said = language_mentions(hello) + language_mentions(after)
    if said:
        reasons.append("talked about languages: " + ", ".join(sorted(set(said))))
    notes = {"stayed silent": not normalize(after)}
    return Verdict(not reasons, reasons, notes)


def judge_unclear_audio(after: str) -> Verdict:
    """Woken by name, then mumbling: it must not announce its language rule."""
    said = language_mentions(after)
    reasons = ["talked about languages: " + ", ".join(said)] if said else []
    notes = {"asked to repeat": asks_to_repeat(after), "stayed silent": not normalize(after)}
    return Verdict(not reasons, reasons, notes)
