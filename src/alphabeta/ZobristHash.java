package dotsandboxes.alphabeta;

import DotsBoxes.board.Board;

import java.util.Random;

/**
 * Gestion du Hash de Zobrist pour le plateau.
 * Utiliser pour identifier les positions dans la table de transposition.
 */
public class ZobristHash {

    // Tableaux pour stocker les nombres aléatoires de chaque arête
    private final long[][] hTable;
    private final long[][] vTable;
    private final long[] playerTable;

    /**
     * Initialisation des tables avec des valeurs aléatoires.
     */
    public ZobristHash(int rows, int cols) {
        Random rng = new Random();

        // Arêtes horizontales
        this.hTable = new long[rows][cols - 1];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols - 1; c++) {
                hTable[r][c] = rng.nextLong();
            }
        }

        // Arêtes verticales
        this.vTable = new long[rows - 1][cols];
        for (int r = 0; r < rows - 1; r++) {
            for (int c = 0; c < cols; c++) {
                vTable[r][c] = rng.nextLong();
            }
        }

        // Joueur courant : 2 valeurs (joueur 0 et joueur 1)
        this.playerTable = new long[2];
        this.playerTable[0] = rng.nextLong();
        this.playerTable[1] = rng.nextLong();
    }

    /**
     * Calcule le hash global du plateau (XOR de toutes les arêtes posées).
     */
    public long compute(Board board) {
        long hash = 0L;

        int rows = board.getRows();
        int cols = board.getCols();

        // XOR des segments horizontaux tracés
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols - 1; c++) {
                if (board.isHEdgeSet(r, c)) {
                    hash ^= hTable[r][c];
                }
            }
        }

        // XOR des segments verticaux tracés
        for (int r = 0; r < rows - 1; r++) {
            for (int c = 0; c < cols; c++) {
                if (board.isVEdgeSet(r, c)) {
                    hash ^= vTable[r][c];
                }
            }
        }

        return hash;
    }

    /**
     * Calcule le hash en incluant qui doit jouer.
     */
    public long computeWithPlayer(Board board, int playerId) {
        return compute(board) ^ playerTable[playerId];
    }
}
