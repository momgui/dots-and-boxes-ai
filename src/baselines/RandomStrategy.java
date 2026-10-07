package baselines;

import engine.Action;
import engine.ActionStrategy;
import engine.Board;

import java.util.List;
import java.util.Random;

/** Draws a uniformly random undrawn edge. */
public class RandomStrategy implements ActionStrategy {

    private final Random rng;

    public RandomStrategy(long seed) {
        this.rng = new Random(seed);
    }

    @Override
    public Action selectAction(Board board, int playerId) {
        List<Action> actions = board.getAvailableActions();
        return actions.get(rng.nextInt(actions.size()));
    }

    @Override
    public String getName() { return "Random"; }
}
