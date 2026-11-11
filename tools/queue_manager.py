#!/usr/bin/env python3
"""
REFERENCE PROTOTYPE — not the production implementation.

The canonical NightLight shuffle lives in the Android app:

    android/app/src/main/java/com/nightlight/app/shuffle/ShuffleOrderFactory.java

This file mirrors that algorithm for experimentation only. It is
NOT a second source of truth: if the two ever disagree, the Java
implementation wins and this file must be brought back in sync.

Model (all soft, never rejects):

    INPUT   tracks + seed + listening history (oldest-first,
            newest last) + shuffle mode

    GENERATE  several complete seeded permutations via constrained
              placement (same-artist / same-song adjacency avoided
              whenever an alternative exists, relaxed automatically
              when the pool leaves no choice)

    SCORE     artist spacing, album spacing, genre diversity,
              title similarity, freshness, recent plays

    SELECT    the best-scoring candidate

    RETURN    the complete order — every track exactly once, always.

Track uniqueness is the ONLY hard rule. A pool that cannot be
spread (12 tracks, 5 from one artist) still produces a valid
queue; the ideal spacing is relaxed, never enforced by rejection.

Usage:
  python3 queue_manager.py tracks.json        # file input
  cat tracks.json | python3 queue_manager.py  # stdin
  python3 queue_manager.py --demo             # built-in sample
  python3 queue_manager.py --seed 7 tracks.json   # reproducible
  python3 queue_manager.py --history h1,h2 tracks.json
"""

from __future__ import annotations

import argparse
import json
import math
import random
import sys
from collections import Counter

# Soft-term weights (mirror of the Java constants).
W_ARTIST = 0.55
W_ALBUM = 0.35
W_GENRE_RUN = 0.30
W_TITLE = 0.50
W_FRESHNESS = 1.9
W_GENRE_BALANCE = 2.2

ARTIST_WINDOW = 8
ALBUM_WINDOW = 4
TITLE_WINDOW = 4

SEED_STRIDE = 0x9E3779B97F4A7C15  # golden-ratio stride per candidate


# ---------------------------------------------------------------- normalizers
# Simplified mirrors of TitleGuard / GenreHelper in the app.
def norm(s):
    return (s or "").strip().lower()


def canonical_title(name):
    t = norm(name)
    for tag in ("(acoustic)", "(live)", "- live", " (remix)", "(remix)",
                "(radio edit)", "(explicit)"):
        t = t.replace(tag, "")
    return " ".join(t.split())


def genre_of(track):
    g = norm(track.get("genre"))
    if g:
        return g
    text = norm(track.get("album", "")) + " " + norm(track.get("artist", ""))
    for token, family in (
            ("pop", "pop"), ("rock", "rock"), ("punk", "rock"),
            ("metal", "metal"), ("jazz", "jazz"), ("electronic", "electronic"),
            ("edm", "electronic"), ("hiphop", "hiphop"), ("rap", "hiphop"),
            ("rnb", "rnb"), ("soul", "rnb"), ("folk", "folk"),
            ("acoustic", "acoustic"), ("indie", "indie"), ("classical", "classical"),
            ("country", "country"), ("latin", "latin"), ("reggae", "reggae"),
            ("blues", "blues"), ("funk", "funk"), ("disco", "disco")):
        if token in text:
            return family
    return "unknown"


# ---------------------------------------------------------------- generation
def plain_order(pool, seed):
    """Uniform Fisher-Yates over the deduped ids."""
    ids = dedup_ids(pool)
    rng = random.Random(seed)
    rng.shuffle(ids)
    return ids


def smart_order(pool, seed, history=None):
    """The canonical order: K complete permutations, best score wins."""
    remaining = dedup_tracks(pool)
    if not remaining:
        return []
    candidates = candidate_count(len(remaining))
    best, best_score, best_adjacency = None, -math.inf, None
    by_id = {t["id"]: t for t in remaining}
    for k in range(candidates):
        cand = sample_order(remaining, seed + SEED_STRIDE * (k + 1), history)
        score = score_order(cand, remaining, history)
        # Score ties (possible when a pool carries no genre or album
        # signal) are broken by the strongest local clumping signal:
        # immediate same-artist or same-song repeats.
        adjacency = immediate_adjacency(cand, by_id)
        if score > best_score or (score == best_score
                                  and (best_adjacency is None or adjacency < best_adjacency)):
            best, best_score, best_adjacency = cand, score, adjacency
    return best


def candidate_count(n):
    if n <= 300:
        return 8
    if n <= 1000:
        return 5
    if n <= 2500:
        return 3
    return 2


def sample_order(pool, seed, history=None):
    """One complete permutation via weighted sampling without replacement.

    Same-artist and same-song adjacency are avoided whenever an
    alternative exists (relaxation ladder) and allowed only when the
    pool leaves no choice. Genre runs and recently-played tracks stay
    soft, so the result still feels random, not alternating.
    """
    remaining = list(pool)
    out = []
    rng = random.Random(seed)
    fresh = {i for i in (history or []) if i}

    artists = {norm(t["artist"]) for t in remaining}
    albums = {norm(t["album"]) for t in remaining}
    artist_window = min(ARTIST_WINDOW, len(artists)) if len(artists) > 1 else 0
    album_window = min(ALBUM_WINDOW, len(albums)) if len(albums) > 1 else 0
    title_window = min(TITLE_WINDOW, len(remaining))

    genre_remaining = Counter(genre_of(t) for t in remaining)
    genre_placed = Counter()

    recent_artists, recent_albums, recent_titles = [], [], []
    run_genre, run_length = None, 0

    while remaining:
        last_artist = recent_artists[0] if recent_artists else None
        last_title = recent_titles[0] if recent_titles else None
        cands = eligible(remaining, last_artist, last_title)

        weights = []
        for t in cands:
            artist, album = norm(t["artist"]), norm(t["album"])
            title, genre = canonical_title(t["title"]), genre_of(t)
            penalty = 0.0

            if artist_window and recent_artists:
                span = min(artist_window, len(recent_artists))
                for j in range(span):
                    if recent_artists[j] == artist:
                        penalty += W_ARTIST * (1.0 - j / span)
                        break
            if album_window and recent_albums:
                span = min(album_window, len(recent_albums))
                for j in range(span):
                    if recent_albums[j] == album:
                        penalty += W_ALBUM * (1.0 - j / span)
                        break
            for j in range(min(title_window, len(recent_titles))):
                rt = recent_titles[j]
                if rt and rt == title:
                    penalty += W_TITLE * (1.0 - j / max(1, title_window))
                    break
            if genre == run_genre:
                penalty += W_GENRE_RUN * run_length
            elif run_length >= 3:
                penalty += W_GENRE_RUN * 0.5
            if t["id"] in fresh:
                penalty += W_FRESHNESS

            balance = genre_balance(genre, genre_remaining, genre_placed,
                                    len(remaining), len(out))
            weights.append(math.exp((balance - penalty)))

        total = sum(weights)
        if not total or total != total:  # zero or NaN
            chosen = cands[rng.randrange(len(cands))]
        else:
            r = rng.random() * total
            chosen = cands[-1]
            for t, w in zip(cands, weights):
                r -= w
                if r <= 0:
                    chosen = t
                    break

        remaining.remove(chosen)
        out.append(chosen["id"])
        genre_remaining[genre_of(chosen)] -= 1
        genre_placed[genre_of(chosen)] += 1
        push(recent_artists, norm(chosen["artist"]), max(1, artist_window))
        push(recent_albums, norm(chosen["album"]), max(1, album_window))
        push(recent_titles, canonical_title(chosen["title"]), title_window)
        g = genre_of(chosen)
        if g == run_genre:
            run_length += 1
        else:
            run_genre, run_length = g, 1
    return out


def eligible(remaining, last_artist, last_title):
    """Relaxation ladder: strict, then no-song-repeat, then no-artist-repeat,
    then anything — so single-artist and one-song pools still terminate."""
    if last_artist or last_title:
        for block_artist, block_title in ((True, True), (False, True), (True, False)):
            out = [t for t in remaining
                   if not (block_artist and last_artist and last_artist == norm(t["artist"]))
                   and not (block_title and last_title and last_title == canonical_title(t["title"]))]
            if out:
                return out
    return list(remaining)


# ---------------------------------------------------------------- scoring
def score_order(order, pool, history=None):
    """Score a COMPLETE order on the soft terms. Higher is better.

    Every term is soft: nothing here can reject an order — a pool
    that cannot be spread simply scores lower.
    """
    n = len(order)
    if n == 0:
        return 0.0
    by_id = {t["id"]: t for t in pool}
    keys = {
        "artist": [norm(by_id[i]["artist"]) for i in order],
        "album": [norm(by_id[i]["album"]) for i in order],
        "title": [canonical_title(by_id[i]["title"]) for i in order],
        "genre": [genre_of(by_id[i]) for i in order],
    }

    penalty = 0.0
    penalty += proximity_penalty(keys["artist"], ARTIST_WINDOW, W_ARTIST)
    penalty += proximity_penalty(keys["album"], ALBUM_WINDOW, W_ALBUM)
    penalty += proximity_penalty(keys["title"], TITLE_WINDOW, W_TITLE)

    # Genre diversity: runs plus prefix over-representation.
    global_genre = Counter(keys["genre"])
    placed_genre = Counter()
    run = 1
    for i in range(n):
        if i > 0:
            run = run + 1 if keys["genre"][i] == keys["genre"][i - 1] else 1
            if run > 1:
                penalty += W_GENRE_RUN * (run - 1)
        placed_genre[keys["genre"][i]] += 1
        prefix = i + 1
        for g, count in global_genre.items():
            expected = count / n
            actual = placed_genre.get(g, 0) / prefix
            over = actual - expected
            if over > 0:
                penalty += W_GENRE_BALANCE * over / n

    # Freshness and recent plays. History is oldest-first, so the
    # newest track carries the largest recency weight. Soft: history
    # tracks stay eligible — pushed later, never dropped.
    if history:
        h = len(history)
        recency = {i: (idx + 1) / h for idx, i in enumerate(history) if i}
        opening_third = max(1, n // 3)
        for i in range(n):
            w = recency.get(order[i])
            if w is None:
                continue
            penalty += W_FRESHNESS * w * (n - i) / n
            if i < opening_third:
                penalty += W_FRESHNESS * 0.5

    return -penalty


def immediate_adjacency(order, by_id):
    """Immediate same-artist or same-song repeats — the strongest
    local clumping signal, used only to break score ties."""
    count = 0
    for i in range(1, len(order)):
        prev, cur = by_id.get(order[i - 1]), by_id.get(order[i])
        if prev is None or cur is None:
            continue
        pa, ca = norm(prev["artist"]), norm(cur["artist"])
        if pa and pa == ca:
            count += 1
        pt, ct = canonical_title(prev["title"]), canonical_title(cur["title"])
        if pt and pt == ct:
            count += 1
    return count


def proximity_penalty(keys, window, weight):
    penalty = 0.0
    for i in range(1, len(keys)):
        if not keys[i]:
            continue
        for j in range(max(0, i - window), i):
            if keys[i] == keys[j]:
                penalty += weight * (window - (i - j)) / window
    return penalty


def genre_balance(genre, remaining_by_genre, placed_by_genre, remaining_total, placed_total):
    if len(remaining_by_genre) <= 1 or placed_total <= 0:
        return 0.0
    remaining_share = remaining_by_genre.get(genre, 0) / remaining_total
    placed_share = placed_by_genre.get(genre, 0) / placed_total
    placed_share = max(placed_share, 0.5 / placed_total)
    return W_GENRE_BALANCE * (math.log(remaining_share) - math.log(placed_share))


def push(window, value, size):
    if not value:
        return
    window.insert(0, value)
    while len(window) > max(1, size):
        window.pop()


# ---------------------------------------------------------------- helpers
def dedup_ids(pool):
    out, seen = [], set()
    for t in pool:
        if t.get("id") and t["id"] not in seen:
            seen.add(t["id"])
            out.append(t["id"])
    return out


def dedup_tracks(pool):
    out, seen = [], set()
    for t in pool:
        if t.get("id") and t["id"] not in seen:
            seen.add(t["id"])
            out.append(t)
    return out


DEMO_TRACKS = [
    {"id": "t01", "title": "Don't Panic", "artist": "Coldplay", "album": "Parachutes"},
    {"id": "t02", "title": "Yellow", "artist": "Coldplay", "album": "Parachutes"},
    {"id": "t03", "title": "Clocks", "artist": "Coldplay", "album": "A Rush of Blood to the Head"},
    {"id": "t04", "title": "The Scientist", "artist": "Coldplay", "album": "A Rush of Blood to the Head"},
    {"id": "t05", "title": "Paranoid Android", "artist": "Radiohead", "album": "OK Computer"},
    {"id": "t06", "title": "Karma Police", "artist": "Radiohead", "album": "OK Computer"},
    {"id": "t07", "title": "Everything in Its Right Place", "artist": "Radiohead", "album": "Kid A"},
    {"id": "t08", "title": "Get Lucky", "artist": "Daft Punk", "album": "Discovery"},
    {"id": "t09", "title": "Instant Crush", "artist": "Daft Punk", "album": "Discovery"},
    {"id": "t10", "title": "Smells Like Teen Spirit", "artist": "Nirvana", "album": "Nevermind"},
    {"id": "t11", "title": "Come as You Are", "artist": "Nirvana", "album": "Nevermind"},
    {"id": "t12", "title": "Hyperballad", "artist": "Björk", "album": "Post"},
]


def load_tracks(source):
    data = sys.stdin.read() if source == "-" else open(source, encoding="utf-8").read()
    tracks = json.loads(data)
    if not isinstance(tracks, list):
        raise ValueError("input must be a JSON array of tracks")
    for t in tracks:
        for key in ("id", "title", "artist", "album"):
            if key not in t:
                raise ValueError(f"track missing required key {key!r}: {t!r}")
    return tracks


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Reference prototype of the NightLight canonical shuffle")
    parser.add_argument("input", nargs="?", default="-",
                        help="JSON file of tracks (default: stdin)")
    parser.add_argument("--seed", type=int, default=None,
                        help="seed for a reproducible shuffle")
    parser.add_argument("--history", default="",
                        help="comma-separated recently-played ids (oldest first)")
    parser.add_argument("--demo", action="store_true",
                        help="run on the built-in sample list")
    args = parser.parse_args(argv)

    tracks = DEMO_TRACKS if args.demo else load_tracks(args.input)
    history = [h for h in args.history.split(",") if h] if args.history else None
    seed = args.seed if args.seed is not None else random.SystemRandom().randrange(1 << 62)
    queue = smart_order(tracks, seed, history)
    assert len(queue) == len(dedup_ids(tracks)), "internal error: incomplete permutation"
    print(json.dumps(queue, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
