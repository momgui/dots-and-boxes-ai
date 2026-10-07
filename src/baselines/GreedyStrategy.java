package baselines;

import engine.Action;
import engine.ActionStrategy;
import engine.Board;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * One-ply greedy player:
 * 1. completes a box whenever it can;
 * 2. otherwise draws an edge that gives no box a third side;
 * 3. otherwise draws a random edge.
 * Ties inside each rule are broken at random.
 */
public class GreedyStrategy implements ActionStrategy {

    private final Random rng;

    public GreedyStrategy(long seed) {
        this.rng = new Random(seed);
    }

    @Override
    public Action selectAction(Board board, int playerId) {
        List<Action> actions = board.getAvailableActions();
        List<Action> closing = new ArrayList<>();
        List<Action> safe = new ArrayList<>();
        for (Action a : actions) {
            int[] after = sidesAfter(board, a);
            boolean closes = false, gives = false;
            for (int s : after) {
                if (s == 4) closes = true;
                if (s == 3) gives = true;
            }
            if (closes) closing.add(a);
            else if (!gives) safe.add(a);
        }
        if (!closing.isEmpty()) return pick(closing);
        if (!safe.isEmpty()) return pick(safe);
        return pick(actions);
    }

    private Action pick(List<Action> list) {
        return list.get(rng.nextInt(list.size()));
    }

    /** Number of drawn sides of each box adjacent to {@code a}, counting {@code a} itself. */
    static int[] sidesAfter(Board b, Action a) {
        int r = a.getRow(), c = a.getCol();
        int boxRows = b.getRows() - 1, boxCols = b.getCols() - 1;
        int[] out = new int[2];
        int n = 0;
        if (a.getType() == Action.Type.HORIZONTAL) {
            if (r > 0) out[n++] = sides(b, r - 1, c) + 1;
            if (r < boxRows) out[n++] = sides(b, r, c) + 1;
        } else {
            if (c > 0) out[n++] = sides(b, r, c - 1) + 1;
            if (c < boxCols) out[n++] = sides(b, r, c) + 1;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    static int sides(Board b, int r, int c) {
        return (b.isHEdgeSet(r, c) ? 1 : 0) + (b.isHEdgeSet(r + 1, c) ? 1 : 0)
             + (b.isVEdgeSet(r, c) ? 1 : 0) + (b.isVEdgeSet(r, c + 1) ? 1 : 0);
    }

    @Override
    public String getName() { return "Greedy"; }
}
