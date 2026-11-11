package com.nightlight.app.reco;

import android.util.Log;

import com.nightlight.app.domain.model.Track;
import com.nightlight.app.smartshuffle.GenreHelper;
import com.nightlight.app.smartshuffle.TitleGuard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic hybrid recommender (§3, §20 + transition-first upgrade):
 * candidate generation → RELATEDNESS GATE → TRANSITION scoring (prev-anchored)
 * → user taste → freshness (AFTER relevance) → MMR diversity → sequential
 * multi-sequence selection (best whole-sequence wins).
 *
 * Round 0 scores against the seed (anchors the sequence start); every later
 * round scores against the previously SELECTED track with an evolving
 * {@link SessionContext}, so D is influenced by A/B/C — a rolling session
 * process, not a static seed-similarity function.
 *
 * SEARCH RESULTS ARE NEVER AN INPUT HERE. The pool comes from labeled
 * generators (radio / liked / favorites / recent-success / trending /
 * exploration); a search list may only supply the SEED track.
 */
public final class RecommendationEngine {

    private static final String TAG = "NightLightReco";

    /** Smart session target: 15–30 tracks ahead (§16). */
    public static final int SESSION_MIN = 15;
    public static final int SESSION_MAX = 30;
    /** Batch size per generation call; top-up triggers at <= 5 remaining. */
    public static final int BATCH = 15;
    public static final int TOPUP_THRESHOLD = 5;
    /** Candidate sequences generated; the best-scoring sequence wins. */
    public static final int SEQUENCE_VARIANTS = 3;

    /**
     * Final safety coherence: below this relatedness a candidate is junk, not
     * "exploration". If nothing qualifies, the batch comes back short (or
     * empty) instead of filled with unrelated music.
     */
    public static final double MIN_COHERENCE = 0.08;

    /**
     * Musical coherence: relatedness minus provider-radio membership.
     * Radio proves the provider returned it, not that it fits — coherence
     * must come from genre/artist/language/mood evidence alone.
     */
    static double coherenceOf(RelatednessScorer.Breakdown bd) {
        if (bd == null) return 0;
        Double radio = bd.parts.get("radio");
        return bd.total - (radio != null ? radio : 0);
    }

    private final RelevanceScorer scorer = new RelevanceScorer();
    private final RelatednessScorer relatedness = new RelatednessScorer();
    private final TransitionScorer transitions = new TransitionScorer();

    /** Per-pick audit record: every score the spec requires logged. */
    public static final class ScoredPick {
        public Track track;
        public String genre = "";
        public String language = "";
        public String sources = "";
        public double relatedness;
        public double transition;
        public double sessionFit;
        public double taste;
        public double freshness;
        public double diversity;
        public double penalty;
        public double finalScore;
        /** Audio-feature similarity when available, else relatedness fallback. */
        public double musicSimilarity;
        /** DIRECT / ADJACENT / BRIDGE / EXPLORATORY / TELEPORT. */
        public String transitionClass = "";
        /** Neighborhood distance prev→pick (session drift). */
        public int drift = -1;
        /** Neighborhood distance session-centroid→pick. */
        public int centroidDist = -1;
        public String reason = "";
    }

    public static final class Result {
        public final List<Track> tracks;
        public final List<String> reasons;
        public final Map<String, Double> topRelevance;
        /** Per-pick audit records, same order as {@link #tracks}. */
        public List<ScoredPick> details = new ArrayList<>();
        /** Pool size before the relatedness floor. */
        public int poolBefore;
        /** Pool size after the floor. */
        public int poolAfter;
        /** Floor ladder level used (0 strict … 3 open). */
        public int floorLevel;
        /** Candidates rejected by the floor (ids, bounded for logs). */
        public List<String> rejectedIds = new ArrayList<>();
        /** Human reject reasons aligned with {@link #rejectedIds}. */
        public List<String> rejectReasons = new ArrayList<>();

        Result(List<Track> tracks, List<String> reasons, Map<String, Double> topRelevance) {
            this.tracks = tracks;
            this.reasons = reasons;
            this.topRelevance = topRelevance;
        }
    }

    public static final class Request {
        public Track seed;
        public List<Track> contextTrail = new ArrayList<>(); // seed + prior picks
        public CandidatePool.Input pool = new CandidatePool.Input();
        public SessionProfile session;
        public TasteProfile taste;
        public String explicitMood;
        public DiscoveryMode mode = DiscoveryMode.BALANCED;
        public int count = BATCH;
        /** Ids already queued/consumed — never re-emitted. */
        public Set<String> excludeIds = new HashSet<>();
        /** Rolled session id for SMART_RECO logs ("" when unknown). */
        public String sessionId = "";
        /** Generation id for SMART_RECO logs (-1 when unknown). */
        public long generationId = -1;
        /**
         * Audio-feature source (nullable). Null = all snapshots unknown:
         * the feature bonus is exactly 0 and totals are unchanged.
         */
        public TrackFeatureProvider featureProvider;
    }

    /**
     * Builds the next batch. Pure + deterministic given inputs (no randomness;
     * ties break by pool order), which is what makes the acceptance tests
     * meaningful.
     */
    public Result nextBatch(Request req) {
        List<Track> tracks = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        Map<String, Double> topRel = new HashMap<>();
        Result result = new Result(tracks, reasons, topRel);
        if (req == null || req.seed == null) return result;

        List<Candidate> pool = CandidatePool.build(req.pool, req.session, req.taste);
        result.poolBefore = pool.size();

        // Evolving vibe FIRST: floor reject-reasons need session language data.
        SessionContext vibe = new SessionContext(req.session, req.seed);
        vibe.seedFromProfile();
        List<Track> trail = req.contextTrail != null ? req.contextTrail : new ArrayList<Track>();
        for (int i = 0; i < trail.size(); i++) {
            Track t = trail.get(i);
            if (t == null) continue;
            Track prev = vibe.prev();
            if (i == 0 && prev != null && prev.id != null && prev.id.equals(t.id)) continue;
            vibe.advance(t);
        }

        // 1. Relatedness gate vs SEED (first-pick anchoring + bad-tail removal)
        // with the fallback ladder (never jumps straight to arbitrary tracks).
        Map<String, Double> relMap = new HashMap<>();
        Map<String, RelatednessScorer.Breakdown> relBreak = new HashMap<>();
        for (Candidate c : pool) {
            RelatednessScorer.Breakdown bd = relatedness.score(req.seed, c);
            relMap.put(c.track.id, bd.total);
            relBreak.put(c.track.id, bd);
        }
        int want = Math.max(1, Math.min(req.count, SESSION_MAX));
        Set<String> excluded = req.excludeIds != null ? req.excludeIds : new HashSet<String>();
        int available = 0;
        for (Candidate c : pool) {
            if (!excluded.contains(c.track.id)) available++;
        }
        List<Candidate> filtered = pool;
        int level = RelatednessScorer.FLOOR_LADDER.length - 1;
        for (int l = 0; l < RelatednessScorer.FLOOR_LADDER.length; l++) {
            double floor = RelatednessScorer.FLOOR_LADDER[l];
            List<Candidate> passers = new ArrayList<>();
            for (Candidate c : pool) {
                if (relMap.getOrDefault(c.track.id, 0.0) >= floor) passers.add(c);
            }
            int emittable = 0;
            for (Candidate c : passers) {
                if (!excluded.contains(c.track.id)) emittable++;
            }
            if (emittable >= Math.min(want, available)) {
                filtered = passers;
                level = l;
                break;
            }
        }
        // 1b. Quality-aware tail (§14): junk never fills the count. Coherence
        // counts musical evidence ONLY — provider-radio membership proves
        // candidacy, not coherence, so it cannot rescue junk here. Short
        // batches beat junk batches; an empty pool yields an empty batch and
        // the player retries on the next top-up instead of playing anything.
        List<Candidate> coherent = new ArrayList<>();
        for (Candidate c : filtered) {
            if (coherenceOf(relBreak.get(c.track.id)) >= MIN_COHERENCE) coherent.add(c);
        }
        if (!coherent.isEmpty()) {
            logRejects(req, pool, coherent, relMap, vibe, result);
            filtered = coherent;
        } else {
            logRejects(req, pool, filtered, relMap, vibe, result);
            filtered = coherent; // empty: nothing meets minimum coherence
        }
        result.poolAfter = filtered.size();
        result.floorLevel = level;

        DiscoveryMode mode = req.mode != null ? req.mode : DiscoveryMode.BALANCED;

        // Context head for separation from now-playing (also vibe-seeded).
        List<Track> head = new ArrayList<>();
        if (req.contextTrail != null) head.addAll(req.contextTrail);
        if (head.isEmpty()) head.add(req.seed);

        // 2. Static taste/freshness parts (seed-anchored, computed once).
        Map<String, Double> tasteMap = new HashMap<>();
        Map<String, Double> freshMap = new HashMap<>();
        RelevanceScorer.Context tasteCtx = baseContext(req);
        for (Candidate c : filtered) {
            RelevanceScorer.Breakdown bd = scorer.score(c, tasteCtx);
            double taste = 0, fresh = 0;
            for (Map.Entry<String, Double> e : bd.parts.entrySet()) {
                String k = e.getKey();
                if (k.equals("user_genre") || k.equals("artist_affinity")
                        || k.equals("mood_compat") || k.equals("language_compat")
                        || k.equals("liked_artist") || k.equals("completion_bonus")) {
                    taste += e.getValue();
                } else if (k.equals("freshness") || k.equals("novelty")) {
                    fresh += e.getValue();
                }
            }
            tasteMap.put(c.track.id, taste);
            freshMap.put(c.track.id, fresh);
        }

        // Feature snapshots, cached once per batch (provider is sync+fast by
        // contract; null provider = all unknown = zero bonus, same totals).
        final Map<String, TrackFeatures> featureCache = new HashMap<>();
        if (req.featureProvider != null) {
            for (Candidate c : filtered) {
                featureCache.put(c.track.id,
                        req.featureProvider.featuresFor(c.track));
            }
            for (Track t : head) {
                if (t != null && t.id != null && !featureCache.containsKey(t.id)) {
                    featureCache.put(t.id, req.featureProvider.featuresFor(t));
                }
            }
        }

        // 3. K sequence variants (λ explore/exploit trade-offs on the SAME
        // pool), best whole-sequence score wins. Length ties prefer coverage.
        double baseLambda = mode.mmrLambda;
        double[] lambdas = {baseLambda, Math.min(0.9, baseLambda + 0.1),
                Math.max(0.3, baseLambda - 0.1)};
        List<Sequence> sequences = new ArrayList<>();
        for (int v = 0; v < SEQUENCE_VARIANTS; v++) {
            sequences.add(runSequence(req, vibe.copy(), new ArrayList<>(filtered),
                    relMap, tasteMap, freshMap, featureCache, lambdas[v], want));
        }
        Sequence best = sequences.get(0);
        for (Sequence s : sequences) {
            if (s.seqScore.total > best.seqScore.total + 1e-9
                    || (Math.abs(s.seqScore.total - best.seqScore.total) <= 1e-9
                        && s.picks.size() > best.picks.size())) {
                best = s;
            }
        }

        // 4. Final invariant (shared with shuffle): no adjacent same titles.
        List<Track> anchor = new ArrayList<>();
        Track headLast = trail.isEmpty() ? req.seed : trail.get(trail.size() - 1);
        anchor.add(headLast != null ? headLast : req.seed);
        for (Sequence.Pick p : best.picks) anchor.add(p.track);
        List<Track> ordered = TitleGuard.decluster(anchor, 0);
        Map<String, Sequence.Pick> pickById = new HashMap<>();
        for (Sequence.Pick p : best.picks) pickById.put(p.track.id, p);
        List<Track> finalTracks = new ArrayList<>();
        for (int i = 1; i < ordered.size(); i++) { // index 0 is the context head
            Sequence.Pick p = pickById.get(ordered.get(i).id);
            if (p == null) continue;
            finalTracks.add(p.track);
            topRel.put(p.track.id, p.relevance);
        }

        for (Track t : finalTracks) tracks.add(t);
        reasons.clear();
        List<ScoredPick> details = new ArrayList<>();
        for (Track t : finalTracks) {
            Sequence.Pick p = pickById.get(t.id);
            reasons.add(p.reason);
            details.add(toScoredPick(p));
        }
        result.details = details;
        logBatch(req, result, best);
        return result;
    }

    /** One full sequential run: transition-first rounds + MMR-1 per round. */
    private Sequence runSequence(Request req, SessionContext vibe, List<Candidate> candidates,
                                 Map<String, Double> relMap, Map<String, Double> tasteMap,
                                 Map<String, Double> freshMap, Map<String, TrackFeatures> featureCache,
                                 double lambda, int want) {
        Sequence seq = new Sequence();
        List<Track> trail = req.contextTrail != null ? req.contextTrail : new ArrayList<Track>();
        List<Track> selected = new ArrayList<>(trail);
        if (selected.isEmpty()) selected.add(req.seed);
        Track prev = selected.get(selected.size() - 1);
        Set<String> excluded = req.excludeIds != null ? req.excludeIds : new HashSet<String>();
        List<Candidate> remaining = new ArrayList<>(candidates);

        while (seq.picks.size() < want && !remaining.isEmpty()) {
            // Support gate: a candidate needs ≥2 independent evidence legs
            // (genre tie, artist tie, radio membership, taste history) to lead.
            // Trending/popularity alone is NEVER enough to outrank related
            // music — weak candidates wait until no strong ones remain.
            List<Candidate> strong = new ArrayList<>();
            for (Candidate c : remaining) {
                if (excluded.contains(c.track.id)) continue;
                if (supportLegs(req.seed, c) >= 2) strong.add(c);
            }
            List<Candidate> roundPool = strong.isEmpty() ? remaining : strong;
            // Transition-first relevance for this round (prev-anchored).
            Map<String, Double> relevance = new HashMap<>();
            Map<String, TransitionScorer.Breakdown> breaks = new HashMap<>();
            Map<String, AudioSimilarity.Result> musicSims = new HashMap<>();
            for (Candidate c : roundPool) {
                if (excluded.contains(c.track.id)) continue;
                double maxSim = maxSimilarity(c.track, selected);
                TransitionScorer.In in = new TransitionScorer.In();
                in.prev = prev;
                in.ctx = vibe;
                in.cand = c;
                in.taste = tasteMap.getOrDefault(c.track.id, 0.0);
                in.freshness = freshMap.getOrDefault(c.track.id, 0.0);
                in.diversity = 1.0 - maxSim;
                in.exploration = c.sources.contains(Candidate.Source.EXPLORATION);
                TransitionScorer.Breakdown tb = transitions.score(in);
                // Audio-feature bonus: bounded, compatibility-only, and zero
                // when either side is unknown — never a penalty, never filler.
                AudioSimilarity.Result audio = audioSimilarity(prev, c.track, featureCache);
                double bonus = 0.10 * audio.similarity * audio.confidence;
                tb.total += bonus;
                tb.add("audio", bonus);
                relevance.put(c.track.id, tb.total);
                breaks.put(c.track.id, tb);
                musicSims.put(c.track.id, audio);
            }
            // MMR AFTER coherence scoring (diversity mechanism, §13 order).
            // Round 0 uses λ=1 (pure transition order): diversity penalizes
            // seed-similarity, which would fight the required seed anchoring
            // of the first pick. Diversity engages from round 1.
            double roundLambda = seq.picks.isEmpty() ? 1.0 : lambda;
            List<MmrRanker.Ranked> round = MmrRanker.rank(
                    roundPool, selected, relevance, roundLambda, 1, req.session);
            if (round.isEmpty()) break;
            MmrRanker.Ranked r = round.get(0);
            Candidate chosen = null;
            for (Candidate c : remaining) {
                if (c.track.id.equals(r.candidate.track.id)) {
                    chosen = c;
                    break;
                }
            }
            if (chosen == null) break;
            remaining.remove(chosen);
            TransitionScorer.Breakdown tb = breaks.get(chosen.track.id);
            AudioSimilarity.Result audio = musicSims.get(chosen.track.id);
            Sequence.Pick pick = new Sequence.Pick();
            pick.track = chosen.track;
            pick.candidate = chosen;
            pick.relevance = r.relevance;
            pick.mmr = r.mmr;
            pick.lambda = roundLambda;
            pick.reason = (tb != null ? tb.reason + " | " : "") + r.reason;
            pick.relatedness = relMap.getOrDefault(chosen.track.id, 0.0);
            pick.transition = tb != null ? tb.transition : 0;
            pick.sessionFit = tb != null ? tb.sessionFit : 0;
            pick.taste = tasteMap.getOrDefault(chosen.track.id, 0.0);
            pick.freshness = freshMap.getOrDefault(chosen.track.id, 0.0);
            pick.penalty = tb != null ? tb.penalty : 0;
            // musicSimilarity: measured audio similarity when features exist
            // on both sides, else the metadata relatedness fallback.
            pick.musicSim = (audio != null && audio.confidence > 0)
                    ? audio.similarity : pick.relatedness;
            // Drift diagnostics: neighborhood distance from the previous pick
            // and from the session centroid (dominant vibe genre).
            pick.drift = MusicNeighborhood.distance(
                    GenreHelper.genreOf(prev), GenreHelper.genreOf(chosen.track));
            String centroid = vibe.dominantGenre();
            pick.centroidDist = centroid.isEmpty() ? -1
                    : MusicNeighborhood.distance(centroid, GenreHelper.genreOf(chosen.track));
            pick.transitionClass = TransitionClass.classify(
                    prev, chosen.track, vibe, supportLegs(req.seed, chosen)).name();
            seq.add(pick);
            // Rolling session: the vibe evolves with every ACCEPTED pick.
            vibe.advance(chosen.track);
            selected.add(chosen.track);
            prev = chosen.track;
        }
        // Whole-sequence score: transition continuity + session/taste/freshness.
        List<Track> pickTracks = new ArrayList<>();
        Map<String, Double> trans = new HashMap<>();
        Map<String, Double> taste = new HashMap<>();
        Map<String, Double> fresh = new HashMap<>();
        for (Sequence.Pick p : seq.picks) {
            pickTracks.add(p.track);
            trans.put(p.track.id, p.transition);
            taste.put(p.track.id, p.taste);
            fresh.put(p.track.id, p.freshness);
        }
        seq.seqScore = SequenceScorer.scoreWithTransitions(
                pickTracks, trans, taste, fresh, req.session);
        return seq;
    }

    /**
     * Independent evidence legs for a candidate vs the seed (§ARCH:
     * exploration/trending/random never equal directly-related candidates):
     * genre tie, artist tie, provider-radio membership, taste history.
     * Round leadership requires ≥ 2 legs.
     */
    static int supportLegs(Track seed, Candidate c) {
        if (seed == null || c == null || c.track == null) return 0;
        int legs = 0;
        String sg = GenreHelper.genreOf(seed);
        String g = GenreHelper.genreOf(c.track);
        if (!g.isEmpty() && (g.equals(sg) || GenreHelper.isRelated(g, sg))) legs++;
        String sa = TitleGuard.norm(seed.artists);
        String ca = TitleGuard.norm(c.track.artists);
        if (!sa.isEmpty() && (sa.equals(ca)
                || RelatednessScorer.sharePrimaryArtist(sa, ca))) legs++;
        if (c.sources.contains(Candidate.Source.RADIO_SEED)
                || c.sources.contains(Candidate.Source.EXTERNAL_SIMILAR)) legs++;
        if (c.sources.contains(Candidate.Source.LIKED_NEIGHBORHOOD)
                || c.sources.contains(Candidate.Source.FAVORITE_ARTIST)
                || c.sources.contains(Candidate.Source.RECENT_SUCCESS)
                || c.sources.contains(Candidate.Source.CARRIED_QUEUE)) legs++;
        return legs;
    }

    private AudioSimilarity.Result audioSimilarity(Track prev, Track cand,
                                                         Map<String, TrackFeatures> cache) {
        TrackFeatures pf = prev != null && prev.id != null ? cache.get(prev.id) : null;
        TrackFeatures cf = cand != null && cand.id != null ? cache.get(cand.id) : null;
        if (pf == null) pf = TrackFeatures.unknown();
        if (cf == null) cf = TrackFeatures.unknown();
        return AudioSimilarity.compare(pf, cf);
    }

    private double maxSimilarity(Track t, List<Track> selected) {
        double max = 0;
        int from = Math.max(0, selected.size() - 8);
        for (int i = from; i < selected.size(); i++) {
            max = Math.max(max, MmrRanker.similarity(t, selected.get(i)));
        }
        return max;
    }

    private RelevanceScorer.Context baseContext(Request req) {
        RelevanceScorer.Context ctx = new RelevanceScorer.Context();
        ctx.seed = req.seed;
        ctx.explicitMood = req.explicitMood;
        ctx.session = req.session;
        ctx.taste = req.taste;
        ctx.mode = req.mode != null ? req.mode : DiscoveryMode.BALANCED;
        return ctx;
    }

    /** Floor reject audit (bounded): WHY the top rejected candidates died. */
    private void logRejects(Request req, List<Candidate> pool, List<Candidate> kept,
                            Map<String, Double> relMap, SessionContext vibe, Result result) {
        Set<String> keptIds = new HashSet<>();
        for (Candidate c : kept) keptIds.add(c.track.id);
        List<Candidate> rejected = new ArrayList<>();
        for (Candidate c : pool) {
            if (!keptIds.contains(c.track.id)) rejected.add(c);
        }
        rejected.sort((a, b) -> Double.compare(
                relMap.getOrDefault(b.track.id, 0.0),
                relMap.getOrDefault(a.track.id, 0.0)));
        int n = Math.min(5, rejected.size());
        for (int i = 0; i < n; i++) {
            Candidate c = rejected.get(i);
            String reason = rejectReason(req, c, vibe);
            result.rejectedIds.add(c.track.id);
            result.rejectReasons.add(reason);
            logD("REJECT candidate=\"" + safe(c.track.name) + " — " + safe(c.track.artists)
                    + "\" reason=\"" + reason + "\"");
        }
        if (rejected.size() > n) {
            logD("REJECT (+" + (rejected.size() - n) + " more below floor)");
        }
    }

    private String rejectReason(Request req, Candidate c, SessionContext vibe) {
        Track t = c.track;
        if (req.session != null && t.id != null) {
            for (Track r : req.session.lastTracks(5)) {
                if (t.id.equals(r.id)) return "recently played";
            }
        }
        if (vibe != null && vibe.size() >= 2) {
            String clang = t.language != null ? t.language.trim().toLowerCase(Locale.ROOT) : "";
            if (!clang.isEmpty() && !vibe.topLanguages(2).contains(clang)
                    && !vibe.isMixingLanguages()) {
                return "language mismatch";
            }
        }
        return "transition score below minimum";
    }

    private ScoredPick toScoredPick(Sequence.Pick p) {
        ScoredPick d = new ScoredPick();
        d.track = p.track;
        d.genre = GenreHelper.genreOf(p.track);
        d.language = p.track.language != null ? p.track.language : "";
        List<String> srcs = new ArrayList<>();
        for (Candidate.Source s : p.candidate.sources) srcs.add(s.name().toLowerCase(Locale.ROOT));
        java.util.Collections.sort(srcs);
        d.sources = srcs.toString();
        d.relatedness = p.relatedness;
        d.transition = p.transition;
        d.sessionFit = p.sessionFit;
        d.taste = p.taste;
        d.freshness = p.freshness;
        d.diversity = p.lambda * p.relevance - p.mmr;
        d.penalty = p.penalty;
        d.finalScore = p.mmr;
        d.musicSimilarity = p.musicSim;
        d.transitionClass = p.transitionClass;
        d.drift = p.drift;
        d.centroidDist = p.centroidDist;
        d.reason = p.reason;
        return d;
    }

    private void logBatch(Request req, Result res, Sequence best) {
        String seedName = req.seed.name != null ? req.seed.name : "?";
        logI("reco seed=" + seedName + " pool=" + res.poolBefore + "->" + res.poolAfter
                + " floor=L" + res.floorLevel + " picked=" + res.tracks.size()
                + " seq=" + SequenceScorer.format(best.seqScore.total));
        if (logDebug()) {
            int step = 0;
            for (ScoredPick d : res.details) {
                logD(smartRecoLine(req, best, d, step++));
            }
        }
    }

    /** SMART_RECO structured line (§25): every score that chose the track. */
    static String smartRecoLine(Request req, Sequence best, ScoredPick d, int step) {
        String prevName = "?";
        List<Track> trail = req.contextTrail;
        if (trail != null && !trail.isEmpty() && trail.get(trail.size() - 1) != null) {
            prevName = trail.get(trail.size() - 1).name;
        }
        return String.format(Locale.US,
                "SMART_RECO sessionId=%s generationId=%d step=%02d class=%s drift=%d centroid=%d "
                        + "previous=\"%s\" selected=\"%s — %s\" "
                        + "source=%s transition=%.2f sessionFit=%.2f taste=%.2f freshness=%.2f "
                        + "languageCompat=%s musicSimilarity=%.2f diversity=%.2f penalty=%.2f final=%.2f "
                        + "reason=\"%s\"",
                req.sessionId == null ? "" : req.sessionId, req.generationId, step,
                d.transitionClass.isEmpty() ? "?" : d.transitionClass, d.drift, d.centroidDist,
                safe(prevName), safe(d.track.name), safe(d.track.artists), d.sources,
                d.transition, d.sessionFit, d.taste, d.freshness,
                langCompatTag(d), d.musicSimilarity, d.diversity,
                d.penalty, d.finalScore, safe(d.reason));
    }

    private static String langCompatTag(ScoredPick d) {
        return d.language.isEmpty() ? "unknown" : d.language;
    }

    private static String safe(String s) {
        return s == null ? "?" : s.replace('"', '\'');
    }

    /**
     * Log access that survives JVM unit tests (android.util.Log is unmocked
     * there): logging must never break recommendation generation.
     */
    static void logI(String s) {
        try {
            Log.i(TAG, s);
        } catch (RuntimeException ignored) {
        }
    }

    static boolean logDebug() {
        try {
            return Log.isLoggable(TAG, Log.DEBUG);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static void logD(String s) {
        try {
            Log.d(TAG, s);
        } catch (RuntimeException ignored) {
        }
    }

    /** Fresh session: seed + first batch (§16: 15–30 ahead). */
    public Result newSession(Request req) {
        if (req != null && req.count < SESSION_MIN) req.count = BATCH;
        return nextBatch(req);
    }

    /** One generated sequence with its audit trail. */
    static final class Sequence {
        static final class Pick {
            Track track;
            Candidate candidate;
            double relevance;
            double mmr;
            /** MMR λ actually used for this pick (round 0 forces 1.0). */
            double lambda;
            String reason = "";
            double relatedness;
            double transition;
            double sessionFit;
            double taste;
            double freshness;
            double penalty;
            double musicSim;
            String transitionClass = "";
            int drift = -1;
            int centroidDist = -1;
        }

        final List<Pick> picks = new ArrayList<>();
        SequenceScorer.SequenceScore seqScore = new SequenceScorer.SequenceScore();

        void add(Pick p) {
            picks.add(p);
        }
    }
}
