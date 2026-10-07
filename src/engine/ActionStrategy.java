package engine;

/** A player: given the current position and its own id (0 or 1), returns the edge to draw. */
public interface ActionStrategy {

    Action selectAction(Board board, int playerId);

    String getName();
}
