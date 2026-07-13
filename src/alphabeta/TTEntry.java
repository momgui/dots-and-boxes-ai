package dotsandboxes.alphabeta;

import DotsBoxes.board.Action;

/**
 * Une entrée dans la table de transposition.
 * On stocke ici le résultat d'un calcul Alpha-Beta pour pouvoir le réutiliser.
 */
public class TTEntry {

    // On définit les types de valeurs possibles pour savoir comment interpréter le score
    public enum Flag {
        EXACT, // Le score est la valeur exacte du noeud
        LOWER_BOUND, // Le score est au moins égal à cette valeur
        UPPER_BOUND // Le score est au plus égal à cette valeur
    }

    // Score trouvé pour ce plateau (soit l'heuristique, soit le score final)
    final int value;

    // Profondeur à laquelle cette valeur a été calculée
    final int depth;

    // Type de la valeur (exacte, borne inférieure ou borne supérieure)
    final Flag flag;

    /**
     * Le meilleur coup qu'on a trouvé ici.
     * On le garde pour le tester en premier la prochaine fois (Move Ordering),
     * Permet de couper l'arbre Alpha-Beta beaucoup plus vite.
     */
    final Action bestAction;

    /**
     * Constructeur pour créer une entrée.
     */
    public TTEntry(int value, int depth, Flag flag, Action bestAction) {
        this.value      = value;
        this.depth      = depth;
        this.flag       = flag;
        this.bestAction = bestAction;
    }
}
