package engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Dots and Boxes position, written from the public rules of the game.
 *
 * <p>The grid has {@code rows x cols} dots, hence {@code (rows-1) x (cols-1)} boxes.
 * Box (r, c) is bounded by horizontal edges (r, c) and (r+1, c) and by vertical
 * edges (r, c) and (r, c+1). Drawing the fourth side of a box gives it to the
 * player who drew it, and that player moves again. The game ends when every edge
 * is drawn; the player with more boxes wins.
 *
 * <p>The board keeps no cached counters: scores and the end-of-game test are
 * derived from the edge and owner arrays. The edge arrays are exposed by
 * reference, so a search can take a move back by clearing the edge and the
 * owners of the adjacent boxes.
 */
public class Board {

    public static final int NO_OWNER = -1;

    private final int rows;
    private final int cols;
    private final boolean[][] hEdges; // [rows][cols - 1]
    private final boolean[][] vEdges; // [rows - 1][cols]
    private final int[][] boxes;      // [rows - 1][cols - 1], owner id or NO_OWNER

    /** A board of {@code rows x cols} dots (at least 2 x 2). */
    public Board(int rows, int cols) {
        if (rows < 2 || cols < 2) throw new IllegalArgumentException("need at least 2x2 dots");
        this.rows = rows;
        this.cols = cols;
        this.hEdges = new boolean[rows][cols - 1];
        this.vEdges = new boolean[rows - 1][cols];
        this.boxes = new int[rows - 1][cols - 1];
        for (int[] line : boxes) java.util.Arrays.fill(line, NO_OWNER);
    }

    /** A board with {@code boxRows x boxCols} boxes. */
    public static Board withBoxes(int boxRows, int boxCols) {
        return new Board(boxRows + 1, boxCols + 1);
    }

    /** Deep copy. */
    public Board(Board other) {
        this.rows = other.rows;
        this.cols = other.cols;
        this.hEdges = new boolean[rows][];
        for (int r = 0; r < rows; r++) hEdges[r] = other.hEdges[r].clone();
        this.vEdges = new boolean[rows - 1][];
        for (int r = 0; r < rows - 1; r++) vEdges[r] = other.vEdges[r].clone();
        this.boxes = new int[rows - 1][];
        for (int r = 0; r < rows - 1; r++) boxes[r] = other.boxes[r].clone();
    }

    /** Number of dot rows. */
    public int getRows() { return rows; }

    /** Number of dot columns. */
    public int getCols() { return cols; }

    public boolean[][] getHEdges() { return hEdges; }
    public boolean[][] getVEdges() { return vEdges; }

    public boolean isHEdgeSet(int r, int c) { return hEdges[r][c]; }
    public boolean isVEdgeSet(int r, int c) { return vEdges[r][c]; }

    /** Owner of box (r, c), or {@link #NO_OWNER}. */
    public int getBoxOwner(int r, int c) { return boxes[r][c]; }

    public int getScore(int playerId) {
        int n = 0;
        for (int[] line : boxes) for (int owner : line) if (owner == playerId) n++;
        return n;
    }

    public boolean isLegal(Action a) {
        int r = a.getRow(), c = a.getCol();
        if (a.getType() == Action.Type.HORIZONTAL) {
            return r >= 0 && r < rows && c >= 0 && c < cols - 1 && !hEdges[r][c];
        }
        return r >= 0 && r < rows - 1 && c >= 0 && c < cols && !vEdges[r][c];
    }

    /** All undrawn edges: horizontal ones row by row, then vertical ones row by row. */
    public List<Action> getAvailableActions() {
        List<Action> list = new ArrayList<>();
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols - 1; c++)
                if (!hEdges[r][c]) list.add(new Action(Action.Type.HORIZONTAL, r, c));
        for (int r = 0; r < rows - 1; r++)
            for (int c = 0; c < cols; c++)
                if (!vEdges[r][c]) list.add(new Action(Action.Type.VERTICAL, r, c));
        return list;
    }

    /** Every edge is drawn (equivalently, every box has an owner). */
    public boolean isFinished() {
        for (int[] line : boxes) for (int owner : line) if (owner == NO_OWNER) return false;
        return true;
    }

    /**
     * Draws the edge for {@code playerId}. Returns the number of boxes it completes
     * (0, 1 or 2); when it is positive, the same player moves again.
     */
    public int apply(Action a, int playerId) {
        int r = a.getRow(), c = a.getCol();
        int closed = 0;
        if (a.getType() == Action.Type.HORIZONTAL) {
            if (hEdges[r][c]) throw new IllegalStateException("edge already drawn: " + a);
            hEdges[r][c] = true;
            if (r > 0 && isComplete(r - 1, c)) { boxes[r - 1][c] = playerId; closed++; }
            if (r < rows - 1 && isComplete(r, c)) { boxes[r][c] = playerId; closed++; }
        } else {
            if (vEdges[r][c]) throw new IllegalStateException("edge already drawn: " + a);
            vEdges[r][c] = true;
            if (c > 0 && isComplete(r, c - 1)) { boxes[r][c - 1] = playerId; closed++; }
            if (c < cols - 1 && isComplete(r, c)) { boxes[r][c] = playerId; closed++; }
        }
        return closed;
    }

    private boolean isComplete(int r, int c) {
        return hEdges[r][c] && hEdges[r + 1][c] && vEdges[r][c] && vEdges[r][c + 1];
    }

    /** Plain-text drawing, for debugging. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                sb.append('+');
                if (c < cols - 1) sb.append(hEdges[r][c] ? "---" : "   ");
            }
            sb.append('\n');
            if (r == rows - 1) break;
            for (int c = 0; c < cols; c++) {
                sb.append(vEdges[r][c] ? '|' : ' ');
                if (c < cols - 1) {
                    int o = boxes[r][c];
                    sb.append(o == NO_OWNER ? "   " : " " + o + " ");
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
