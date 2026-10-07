package arena;

import baselines.GreedyStrategy;
import baselines.RandomStrategy;
import dotsandboxes.mcts.ParallelMCTSStrategy;
import engine.Action;
import engine.ActionStrategy;
import engine.Board;

import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.*;

/**
 * Plays N games between two strategies and prints a summary.
 *
 * <pre>
 * java -cp out arena.Arena A B [--games N] [--size K] [--seed S] [--mcts-ms T]
 *                              [--parallel P] [--png FILE]
 *   A, B        alphabeta | mcts | greedy | random
 *   --games     number of games (default 10); A moves first in games 1, 3, 5, ...
 *               and B in games 2, 4, 6, ...
 *   --size      board of K x K boxes (default 5)
 *   --seed      base seed for the Random/Greedy players (default 2027)
 *   --mcts-ms   MCTS thinking time per move in ms (default 250)
 *   --parallel  games played at once, each in its own thread with its own agents
 *               (default 1; keep 1 when MCTS plays, it already uses every core)
 *   --png       write the final position of game 1 to this PNG file
 * </pre>
 * Agents print debug lines to stdout; those are discarded, the arena reports on the
 * original stdout.
 */
public class Arena {

    // ---------------------------------------------------------------- options

    private static String kindA, kindB;
    private static int games = 10, size = 5, parallel = 1;
    private static long seed = 2027, mctsMs = 250;
    private static String png;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        List<String> pos = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--games"    -> games = Integer.parseInt(args[++i]);
                case "--size"     -> size = Integer.parseInt(args[++i]);
                case "--seed"     -> seed = Long.parseLong(args[++i]);
                case "--mcts-ms"  -> mctsMs = Long.parseLong(args[++i]);
                case "--parallel" -> parallel = Integer.parseInt(args[++i]);
                case "--png"      -> png = args[++i];
                default           -> pos.add(args[i]);
            }
        }
        if (pos.size() != 2) {
            System.err.println("usage: Arena <alphabeta|mcts|greedy|random> <alphabeta|mcts|greedy|random> "
                    + "[--games N] [--size K] [--seed S] [--mcts-ms T] [--parallel P] [--png FILE]");
            System.exit(2);
        }
        kindA = pos.get(0);
        kindB = pos.get(1);

        PrintStream out = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        try {
            run(out);
        } finally {
            out.flush();
            // The MCTS agent keeps a thread pool alive; end the JVM explicitly.
            System.exit(0);
        }
    }

    // ---------------------------------------------------------------- per-game record

    record GameResult(int index, boolean aFirst, int scoreA, int scoreB, String illegal,
                      long thinkNanosA, long thinkNanosB, int movesA, int movesB,
                      List<Integer> abDepths, List<Boolean> abSolved, long abNodes, long abNanos,
                      long ttHits, long ttLookups, long mctsPlayouts, int mctsMoves, Board finalBoard) {}

    /** Agents owned by one worker thread, reused across that thread's games. */
    static final class Seat {
        InstrumentedAlphaBeta ab;
        ParallelMCTSStrategy mcts;
    }

    private static ActionStrategy make(String kind, Seat seat, long s) {
        return switch (kind) {
            case "alphabeta" -> seat.ab != null ? seat.ab : (seat.ab = new InstrumentedAlphaBeta());
            case "mcts"      -> seat.mcts != null ? seat.mcts : (seat.mcts = new ParallelMCTSStrategy(mctsMs));
            case "greedy"    -> new GreedyStrategy(s);
            case "random"    -> new RandomStrategy(s);
            default -> throw new IllegalArgumentException("unknown player: " + kind);
        };
    }

    private static String label(String kind) {
        return switch (kind) {
            case "alphabeta" -> "Alpha-beta";
            case "mcts" -> "MCTS";
            case "greedy" -> "Greedy";
            case "random" -> "Random";
            default -> kind;
        };
    }

    // ---------------------------------------------------------------- one game

    private static GameResult play(int index, Seat seatA, Seat seatB) {
        boolean aFirst = (index % 2 == 0);
        // Same seat object would make A and B share an agent instance: give each side its own.
        ActionStrategy a = make(kindA, seatA, seed * 1_000_003L + 2L * index);
        ActionStrategy b = make(kindB, seatB, seed * 1_000_003L + 2L * index + 1);
        ActionStrategy[] byId = aFirst ? new ActionStrategy[]{a, b} : new ActionStrategy[]{b, a};
        int idA = aFirst ? 0 : 1;

        Board board = Board.withBoxes(size, size);
        int current = 0;
        long[] think = new long[2];
        int[] moves = new int[2];
        List<Integer> depths = new ArrayList<>();
        List<Boolean> solved = new ArrayList<>();
        long abNodes = 0, abNanos = 0, ttHits = 0, ttLookups = 0, playouts = 0;
        int mctsMoves = 0;
        String illegal = null;

        while (!board.isFinished()) {
            ActionStrategy p = byId[current];
            int available = board.getAvailableActions().size();
            long nodesBefore = (p instanceof InstrumentedAlphaBeta ab) ? ab.getNodes() : 0;

            long t0 = System.nanoTime();
            Action move = p.selectAction(new Board(board), current);
            long dt = System.nanoTime() - t0;

            think[current] += dt;
            moves[current]++;
            if (p instanceof InstrumentedAlphaBeta ab) {
                abNodes += ab.getNodes() - nodesBefore;
                abNanos += dt;
                depths.add(ab.getProf());
                solved.add(ab.getProf() >= available);
                // The table's counters are reset at the start of each game; keep the latest.
                ttHits = ab.getTableTransposition().getHits();
                ttLookups = ab.getTableTransposition().getConsultations();
            } else if (p instanceof ParallelMCTSStrategy m) {
                playouts += m.getDernierTotalSimulations();
                mctsMoves++;
            }

            if (move == null || !board.isLegal(move)) {
                illegal = p.getName() + " returned " + move;
                break;
            }
            int closed = board.apply(move, current);
            if (closed == 0) current = 1 - current;
        }

        int sA = board.getScore(idA), sB = board.getScore(1 - idA);
        if (illegal != null) {
            // Forfeit: the offending side gets 0, the other side every box.
            int total = size * size;
            boolean aFault = byId[current] == a;
            sA = aFault ? 0 : total;
            sB = aFault ? total : 0;
        }
        return new GameResult(index, aFirst, sA, sB, illegal,
                think[idA], think[1 - idA], moves[idA], moves[1 - idA],
                depths, solved, abNodes, abNanos, ttHits, ttLookups, playouts, mctsMoves, board);
    }

    // ---------------------------------------------------------------- driver

    private static void run(PrintStream out) throws Exception {
        String nameA = label(kindA), nameB = label(kindB);
        out.printf("=== %s (A) vs %s (B) | %dx%d boxes | %d games | seed %d | %d game(s) at a time ===%n",
                nameA, nameB, size, size, games, seed, parallel);
        out.printf("machine: %s, %d logical cores, Java %s, max heap %d MiB%n",
                System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors(),
                System.getProperty("java.version"), Runtime.getRuntime().maxMemory() >> 20);
        for (String k : new String[]{kindA, kindB}) {
            if (k.equals("alphabeta"))
                out.println("alpha-beta clock: built into the agent, 1 s per edge per game ("
                        + edges() + " s on this board), per move = remaining / (legal moves / 4 + 1), clamped to [50 ms, 15 s]");
            if (k.equals("mcts"))
                out.printf("mcts budget: %d ms per move, %d threads%n", mctsMs, Runtime.getRuntime().availableProcessors());
        }
        if (kindA.equals(kindB)) out.println("note: both sides use separate agent instances");
        out.println("A moves first in games 1, 3, 5, ...; B in games 2, 4, 6, ...");
        out.println();

        long wall0 = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        ThreadLocal<Seat[]> seats = ThreadLocal.withInitial(() -> new Seat[]{new Seat(), new Seat()});
        CompletionService<GameResult> cs = new ExecutorCompletionService<>(pool);
        for (int i = 0; i < games; i++) {
            final int idx = i;
            cs.submit(() -> { Seat[] s = seats.get(); return play(idx, s[0], s[1]); });
        }
        GameResult[] results = new GameResult[games];
        int next = 0; // print in game order as soon as the prefix is complete
        for (int done = 0; done < games; done++) {
            GameResult got = cs.take().get();
            results[got.index()] = got;
            for (; next < games && results[next] != null; next++) {
            GameResult r = results[next];
            out.printf("game %3d  first=%s  %s %2d - %2d %s  think A %6.1f s  B %6.1f s%s%n",
                    r.index() + 1, r.aFirst() ? "A" : "B", nameA, r.scoreA(), r.scoreB(), nameB,
                    r.thinkNanosA() / 1e9, r.thinkNanosB() / 1e9,
                    r.illegal() != null ? "  ILLEGAL MOVE: " + r.illegal() : "");
            }
        }
        pool.shutdown();
        double wall = (System.nanoTime() - wall0) / 1e9;

        summarize(out, results, nameA, nameB, wall);

        if (png != null) {
            GameResult g = results[0];
            String[] names = g.aFirst() ? new String[]{nameA, nameB} : new String[]{nameB, nameA};
            String[] initials = new String[]{initial(names[0]), initial(names[1])};
            String caption = String.format("Final position of game 1 (%s moved first), %dx%d boxes, seed %d",
                    names[0], size, size, seed);
            BoardImage.write(g.finalBoard(), names, initials, caption, new File(png));
            out.println("wrote " + png);
        }
    }

    private static String initial(String name) {
        return switch (name) {
            case "Alpha-beta" -> "AB";
            case "MCTS" -> "M";
            case "Greedy" -> "G";
            case "Random" -> "R";
            default -> name.substring(0, 1);
        };
    }

    private static int edges() { return 2 * size * (size + 1); }

    private static void summarize(PrintStream out, GameResult[] rs, String nameA, String nameB, double wall) {
        int w = 0, d = 0, l = 0, illegal = 0;
        int[] wF = new int[3], wS = new int[3]; // W/D/L for A moving first / second
        double sum = 0, sumSq = 0;
        long thinkA = 0, thinkB = 0;
        int movesA = 0, movesB = 0;
        for (GameResult r : rs) {
            int diff = r.scoreA() - r.scoreB();
            int k = diff > 0 ? 0 : diff == 0 ? 1 : 2;
            if (k == 0) w++; else if (k == 1) d++; else l++;
            (r.aFirst() ? wF : wS)[k]++;
            if (r.illegal() != null) illegal++;
            sum += diff;
            sumSq += (double) diff * diff;
            thinkA += r.thinkNanosA();
            thinkB += r.thinkNanosB();
            movesA += r.movesA();
            movesB += r.movesB();
        }
        int n = rs.length;
        double mean = sum / n;
        double sd = n > 1 ? Math.sqrt(Math.max(0, (sumSq - n * mean * mean) / (n - 1))) : 0;

        out.println();
        out.printf("--- summary: %s (A) vs %s (B), %d games ---%n", nameA, nameB, n);
        out.printf("A wins / draws / losses : %d / %d / %d   (A win rate %.1f%%)%n", w, d, l, 100.0 * w / n);
        out.printf("  A moving first        : %d / %d / %d%n", wF[0], wF[1], wF[2]);
        out.printf("  A moving second       : %d / %d / %d%n", wS[0], wS[1], wS[2]);
        out.printf("mean score difference   : %+.2f boxes for A (sd %.2f, out of %d boxes)%n", mean, sd, size * size);
        out.printf("think time per game     : A %.1f s, B %.1f s (mean)%n", thinkA / 1e9 / n, thinkB / 1e9 / n);
        out.printf("moves per game          : A %.1f, B %.1f (mean, a capture and the extra move count separately)%n",
                (double) movesA / n, (double) movesB / n);
        if (illegal > 0) out.printf("illegal moves (forfeits): %d%n", illegal);

        // Alpha-beta search statistics, read through public getters and a node counter.
        List<Integer> limited = new ArrayList<>();
        int abMoves = 0, solvedMoves = 0;
        long nodes = 0, nanos = 0, hits = 0, lookups = 0;
        List<Integer> firstDepth = new ArrayList<>();
        for (GameResult r : rs) {
            for (int i = 0; i < r.abDepths().size(); i++) {
                abMoves++;
                if (r.abSolved().get(i)) solvedMoves++; else limited.add(r.abDepths().get(i));
            }
            if (!r.abDepths().isEmpty()) firstDepth.add(r.abDepths().get(0));
            nodes += r.abNodes();
            nanos += r.abNanos();
            hits += r.ttHits();
            lookups += r.ttLookups();
        }
        if (abMoves > 0) {
            Collections.sort(limited);
            out.printf("alpha-beta moves        : %d, of which %d searched to the end of the game, %d stopped by the clock%n",
                    abMoves, solvedMoves, limited.size());
            if (!limited.isEmpty()) {
                out.printf("  completed depth when stopped by the clock: mean %.1f, median %d, min %d, max %d%n",
                        limited.stream().mapToInt(Integer::intValue).average().orElse(0),
                        limited.get(limited.size() / 2), limited.get(0), limited.get(limited.size() - 1));
            }
            out.printf("  completed depth on its first move of each game: mean %.1f%n",
                    firstDepth.stream().mapToInt(Integer::intValue).average().orElse(0));
            out.printf("  search speed          : %.0f nodes/s (%d nodes in %.1f s of thinking)%n",
                    nodes / (nanos / 1e9), nodes, nanos / 1e9);
            out.printf("  TT hit rate           : %.1f%% (%d hits / %d lookups, agent's own counters, per game, summed)%n",
                    lookups > 0 ? 100.0 * hits / lookups : 0.0, hits, lookups);
        }
        long playouts = 0;
        int mMoves = 0;
        for (GameResult r : rs) { playouts += r.mctsPlayouts(); mMoves += r.mctsMoves(); }
        if (mMoves > 0) {
            out.printf("mcts playouts per move  : %.0f (mean over %d moves)%n", (double) playouts / mMoves, mMoves);
        }
        out.printf("wall time               : %.1f s%n", wall);
        out.println();
    }
}
