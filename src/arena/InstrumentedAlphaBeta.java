package arena;

import dotsandboxes.alphabeta.AlphaBetaTTStrategy;
import engine.Board;

/**
 * The tournament alpha-beta agent, unchanged, with a node counter.
 *
 * {@code alphaBetaTT} is public and recursive through {@code this}, so overriding it
 * counts every search node without touching the agent's code. The only extra work
 * per node is one increment.
 */
public class InstrumentedAlphaBeta extends AlphaBetaTTStrategy {

    private long nodes;

    @Override
    public int alphaBetaTT(Board board, int depth, int joueurCourant, int joueurPrincipal,
                           int score, int alpha, int beta, long deadline) {
        nodes++;
        return super.alphaBetaTT(board, depth, joueurCourant, joueurPrincipal, score, alpha, beta, deadline);
    }

    public long getNodes() { return nodes; }
}
