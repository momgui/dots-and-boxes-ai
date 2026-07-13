package dotsandboxes.alphabeta;

import DotsBoxes.board.Action;
import java.util.Arrays;

/**
 * Table de transposition pour éviter de recalculer des positions déjà vues.
 * C'est un cache qui stocke les résultats de l'Alpha-Beta.
 * Version optimisée avec un "Two-Tier Array" (Tableau à deux niveaux)
 * pour gérer parfaitement les collisions de Hash sans perdre les nœuds
 * profonds.
 */
public class transpositionv2 {



    // NIVEAU 1 : "Depth-Preferred" (Ne s'écrase que par un nœud plus profond)
    // Sauvegarde les énormes branches d'exploration.
    private final TTEntry[] tableDepth;
    private final long[] hashKeysDepth;

    // NIVEAU 2 : "Always-Replace" (S'écrase toujours par le dernier nœud calculé)
    // Garantit d'avoir l'information fraîche de l'arbre tout récent.
    private final TTEntry[] tableAlways;
    private final long[] hashKeysAlways;

    // Pour voir si la table est efficace (stats)
    private int size;
    private int hits;
    private int consultations;


    private final int TAILLE_MAX;
    private final int INDEX_MASK;

    /**
     * Constructeur pour initialiser une table de transposition vide.
     */
    public transpositionv2() {
        long safetyRam = Runtime.getRuntime().maxMemory() / 2;
        // Choix de la taille (rappel : 1 million = ~100 Mo)
        if (safetyRam >= 1_000_000_000L) { // > 1 GiB safe
            TAILLE_MAX = 4194304;    // 2^22 (4 Millions d'entrées)
        } else if (safetyRam >= 500_000_000L) { // > 500 MiB safe
            TAILLE_MAX = 2097152;    // 2^21 (2 Millions d'entrées)
        } else {
            TAILLE_MAX = 1048576;    // 2^20 (1 Million d'entrées = valeur sûre)
        }

        INDEX_MASK = TAILLE_MAX - 1;
        this.tableDepth     = new TTEntry[TAILLE_MAX];
        this.hashKeysDepth  = new long[TAILLE_MAX];
        this.tableAlways = new TTEntry[TAILLE_MAX];
        this.hashKeysAlways = new long[TAILLE_MAX];
        this.size = 0;
        this.hits = 0;
        this.consultations = 0;
    }

    // Enregistre un résultat dans la table
    public void store(long hash, int value, int depth, TTEntry.Flag flag, Action bestAction) {
        int index = (int) (hash & INDEX_MASK);

        // --- 1. Mise à jour de la table "Depth-Preferred" ---
        TTEntry entreeProf = tableDepth[index];
        if (entreeProf == null || hashKeysDepth[index] == hash) {
            // Case vide ou même hash : on met à jour si la profondeur est >=
            if (entreeProf == null || depth >= entreeProf.depth) {
                if (entreeProf == null)
                    size++;
                tableDepth[index] = new TTEntry(value, depth, flag, bestAction);
                hashKeysDepth[index] = hash;
            }
        } else if (depth >= entreeProf.depth) {
            // COLLISION : l'index est pris par un hash différent.
            // Le nouveau calcul est plus profond ! On a le droit d'évincer l'ancien.
            tableDepth[index] = new TTEntry(value, depth, flag, bestAction);
            hashKeysDepth[index] = hash;
        }

        // --- 2. Mise à jour de la table "Always-Replace" ---
        // Quoi qu'il arrive, on garde la trace du calcul le plus récent ici.
        tableAlways[index] = new TTEntry(value, depth, flag, bestAction);
        hashKeysAlways[index] = hash;
    }

    // Cherche si on a déjà calculé cette position
    public TTEntry lookup(long hash) {
        consultations++;
        int index = (int) (hash & INDEX_MASK);

        // On check en priorité la table Profonde (ce sont les data de meilleure
        // qualité)
        if (hashKeysDepth[index] == hash) {
            TTEntry entree = tableDepth[index];
            if (entree != null) {
                hits++;
                return entree;
            }
        }

        // Si introuvable on check la table Récente
        if (hashKeysAlways[index] == hash) {
            TTEntry entree = tableAlways[index];
            if (entree != null) {
                hits++;
                return entree;
            }
        }

        return null;
    }

    // Vide la table (uniquement appelé entre deux parties différentes)
    public void clear() {
        Arrays.fill(tableDepth, null);
        Arrays.fill(tableAlways, null);
        size = 0;
        hits = 0;
        consultations = 0;
    }

    // Pour afficher si la table a bien servi pendant le tour
    public void printStats() {
        double tauxHits = (consultations > 0)
                ? (hits * 100.0 / consultations)
                : 0.0;
        System.out.printf("Table de transposition : %d entrées | %d/%d hits (%.1f%%)%n",
                size, hits, consultations, tauxHits);
    }

    // Getters pour les stats
    public int getSize() {
        return size;
    }

    public int getHits() {
        return hits;
    }

    public int getConsultations() {
        return consultations;
    }
}
