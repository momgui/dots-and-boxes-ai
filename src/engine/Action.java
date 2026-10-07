package engine;

/**
 * One move: drawing a single edge between two adjacent dots.
 *
 * Coordinates are dot coordinates on a grid of {@code rows x cols} dots.
 * <ul>
 *   <li>HORIZONTAL (row, col) joins dot (row, col) to dot (row, col + 1);
 *       row in [0, rows), col in [0, cols - 1).</li>
 *   <li>VERTICAL (row, col) joins dot (row, col) to dot (row + 1, col);
 *       row in [0, rows - 1), col in [0, cols).</li>
 * </ul>
 * Immutable value object (usable as a map key).
 */
public final class Action {

    public enum Type { HORIZONTAL, VERTICAL }

    private final Type type;
    private final int row;
    private final int col;

    public Action(Type type, int row, int col) {
        this.type = type;
        this.row = row;
        this.col = col;
    }

    public Type getType() { return type; }
    public int getRow()   { return row; }
    public int getCol()   { return col; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Action)) return false;
        Action a = (Action) o;
        return type == a.type && row == a.row && col == a.col;
    }

    @Override
    public int hashCode() {
        return (type.ordinal() * 1031 + row) * 1031 + col;
    }

    @Override
    public String toString() {
        return (type == Type.HORIZONTAL ? "H" : "V") + "(" + row + "," + col + ")";
    }
}
