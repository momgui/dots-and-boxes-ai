package dotsandboxes.alphabeta;

import DotsBoxes.board.Board;

/**
 * Heuristique v2 optimisée pour Dots and Boxes + Alpha-Beta.
 *
 * Principes de conception :
 *   - PERFORMANCE : zéro allocation, un seul parcours des actions, pas de récursion lourde
 *   - Analyse de chaînes/boucles intégrée dans le scan des cases (pas d'exploration récursive séparée)
 *   - Poids adaptatifs selon la phase de jeu
 *   - Mobilité pondérée (giving 2 ≠ giving 1)
 *   - Score compté une seule fois
 */
public class heuristicv2 {

    // =========================================================================
    // ÉVALUATION PRINCIPALE — optimisée pour être appelée à chaque nœud feuille
    // =========================================================================

    public static int evaluate(Board board, int playerId, int currentPlayerAtLeaf) {
        int opponent = 1 - playerId;
        int rows = board.getRows() - 1;  // nombre de cases en lignes
        int cols = board.getCols() - 1;  // nombre de cases en colonnes

        // =====================
        // 1. PHASE DE JEU — calculée sans appeler getAvailableActions()
        // =====================
        int totalEdges = board.getRows() * cols + rows * board.getCols();
        int playedEdges = countPlayedEdges(board, rows, cols);
        double phase = (double) playedEdges / totalEdges;

        // =====================
        // 2. SCORE — une seule fois
        // =====================
        int score = board.getScore(playerId) - board.getScore(opponent);
        int scoreWeight = (int) (10000 + 10000 * phase);
        int value = score * scoreWeight;

        // =====================
        // 3. SCAN DES CASES — un seul parcours pour tout calculer
        //    + analyse de chaînes via Union-Find intégré
        // =====================
        int dangerousBoxes = 0;  // cases à 3 côtés

        // Union-Find léger pour regrouper les composantes connexes
        int totalBoxes = rows * cols;
        int[] parent = new int[totalBoxes];
        int[] rank = new int[totalBoxes];
        int[] compSize = new int[totalBoxes];
        boolean[] compHasThree = new boolean[totalBoxes]; // la composante a une case à 3 côtés ?
        int[] boxSides = new int[totalBoxes]; // nb de côtés de chaque case

        for (int i = 0; i < totalBoxes; i++) {
            parent[i] = i;
            compSize[i] = 1;
        }

        // Premier passage : compter les côtés de chaque case libre
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (board.getBoxOwner(r, c) != -1) {
                    boxSides[r * cols + c] = -1; // marquée comme prise
                    continue;
                }

                int sides = countEdges(board, r, c);
                int idx = r * cols + c;
                boxSides[idx] = sides;

                if (sides == 3) {
                    dangerousBoxes++;
                    compHasThree[idx] = true;
                }

                // Union avec le voisin du haut si connecté (arête commune non tracée)
                if (sides >= 2 && r > 0) {
                    int upIdx = (r - 1) * cols + c;
                    if (boxSides[upIdx] >= 2 && !board.isHEdgeSet(r, c)) {
                        union(parent, rank, compSize, compHasThree, idx, upIdx);
                    }
                }

                // Union avec le voisin de gauche si connecté
                if (sides >= 2 && c > 0) {
                    int leftIdx = r * cols + (c - 1);
                    if (boxSides[leftIdx] >= 2 && !board.isVEdgeSet(r, c)) {
                        union(parent, rank, compSize, compHasThree, idx, leftIdx);
                    }
                }
            }
        }

        // Deuxième passage : extraire les chaînes et boucles des composantes
        boolean[] seen = new boolean[totalBoxes];
        int longChains = 0;

        int loops = 0;
        int capturableBoxes = 0;
        // Pour le double-dealing on stocke les tailles (max ~25 chaînes sur un plateau raisonnable)
        int[] chainSizesArr = new int[totalBoxes]; // surdimensionné volontairement
        int chainCount = 0;

        for (int i = 0; i < totalBoxes; i++) {
            if (boxSides[i] < 2) continue; // case libre avec <2 côtés ou case prise
            int root = find(parent, i);
            if (seen[root]) continue;
            seen[root] = true;

            int size = compSize[root];
            boolean hasThree = compHasThree[root];

            if (hasThree) {
                // C'est une chaîne (a au moins une entrée à 3 côtés)
                capturableBoxes += size;
                if (size >= 3) {
                    longChains++;
                    chainSizesArr[chainCount++] = size;
                }
            } else if (size >= 4) {
                // Boucle (pas d'entrée, cycle de cases à 2 côtés)
                loops++;
                chainSizesArr[chainCount++] = size;
            }
        }

        // =====================
        // 4. CALCUL DE LA PARITÉ DES CHAÎNES LONGUES
        // =====================
        int longStructures = longChains + loops;
        int chainWeight = (int) (500 + 2500 * phase);

        if (longStructures > 0) {
            int safeMoveCount = countSafeMoves(board, rows, cols);

            boolean isOurTurn = (playerId == currentPlayerAtLeaf);

            // Si le nombre de coups sûrs est pair, celui qui a le trait MAINTENANT va ouvrir.
            boolean leafPlayerOpens = (safeMoveCount % 2 == 0);

            // Est-ce que c'est NOUS qui allons ouvrir la première chaîne ?
            boolean weOpenFirst = isOurTurn ? leafPlayerOpens : !leafPlayerOpens;

            // Si on ouvre en premier, on veut un nombre PAIR de longues structures (stratégie Double-Cross)
            boolean parityGood = weOpenFirst
                    ? (longStructures % 2 == 0)
                    : (longStructures % 2 == 1);

            value += parityGood ? chainWeight * 3 : -chainWeight * 3;

            // Double-dealing : gain net estimé
            if (chainCount > 0) {
                for (int i = 1; i < chainCount; i++) {
                    int key = chainSizesArr[i];
                    int j = i - 1;
                    while (j >= 0 && chainSizesArr[j] < key) {
                        chainSizesArr[j + 1] = chainSizesArr[j];
                        j--;
                    }
                    chainSizesArr[j + 1] = key;
                }

                int netGain = 0;
                for (int i = 0; i < chainCount; i++) {
                    int sz = chainSizesArr[i];
                    if (i < chainCount - 1 && sz > 2) {
                        netGain += (sz - 2);
                    } else {
                        netGain += sz;
                    }
                }
                value += parityGood ? netGain * 300 : -netGain * 300;
            }
        }

        boolean isOurTurn = (playerId == currentPlayerAtLeaf);
        int dangerWeight = (int) (50 + 200 * phase);

        if (isOurTurn) {
            // C'est notre tour
            value += capturableBoxes * chainWeight;
        } else {
            // C'est le tour adverse
            value -= capturableBoxes * chainWeight;
        }

        // Les cases dangereuses (2 côtés) sont une tension structurelle du plateau.
        // On les pénalise toujours de la même façon, peu importe à qui c'est le tour.
        value -= dangerousBoxes * dangerWeight;

        // Pénalité chaînes longues (tension globale du plateau, reste absolue)
        value -= longChains * (int) (200 + 800 * phase);

        // =====================
        // 6. MOBILITÉ PONDÉRÉE — scan direct des arêtes, sans allouer de liste
        // =====================
        int mobilityWeight = (int) (150 * (1 - phase));
        int totalMobilityScore = 0;

        if (mobilityWeight > 0) {
            // Arêtes horizontales
            for (int r = 0; r < board.getRows(); r++) {
                for (int c = 0; c < cols; c++) {
                    if (board.isHEdgeSet(r, c)) continue;
                    int closes = countClosingH(board, r, c, rows, cols);
                    int gives = (closes == 0) ? countGivingH(board, r, c, rows, cols) : 0;
                    totalMobilityScore += mobilityScore(closes, gives, mobilityWeight);
                }
            }
            // Arêtes verticales
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < board.getCols(); c++) {
                    if (board.isVEdgeSet(r, c)) continue;
                    int closes = countClosingV(board, r, c, rows, cols);
                    int gives = (closes == 0) ? countGivingV(board, r, c, rows, cols) : 0;
                    totalMobilityScore += mobilityScore(closes, gives, mobilityWeight);
                }
            }
        }

        // On ajoute la mobilité de manière neutre.
        value += totalMobilityScore;

        return value;
    }

    // =========================================================================
    // MOBILITÉ — calcul inline sans passer par Action/List
    // =========================================================================

    private static int mobilityScore(int closes, int gives, int w) {
        if (closes == 2) return 5 * w;
        if (closes == 1) return 3 * w;
        if (gives == 0) return w / 5;
        if (gives == 1) return -2 * w;
        return -5 * w;  // gives == 2
    }

    // Compte combien de cases cette arête horizontale (r,c) fermerait
    private static int countClosingH(Board board, int r, int c, int rows, int cols) {
        int count = 0;
        // Case en dessous (r, c) : besoin de hEdges[r+1][c], vEdges[r][c], vEdges[r][c+1]
        if (r < rows && c < cols
                && board.isHEdgeSet(r + 1, c) && board.isVEdgeSet(r, c) && board.isVEdgeSet(r, c + 1))
            count++;
        // Case au-dessus (r-1, c) : besoin de hEdges[r-1][c], vEdges[r-1][c], vEdges[r-1][c+1]
        if (r > 0 && c < cols
                && board.isHEdgeSet(r - 1, c) && board.isVEdgeSet(r - 1, c) && board.isVEdgeSet(r - 1, c + 1))
            count++;
        return count;
    }

    // Compte combien de cases cette arête horizontale (r,c) "donnerait" (passera à 3 côtés)
    private static int countGivingH(Board board, int r, int c, int rows, int cols) {
        int count = 0;
        if (r < rows && c < cols) {
            int sides = (board.isHEdgeSet(r + 1, c) ? 1 : 0)
                      + (board.isVEdgeSet(r, c) ? 1 : 0)
                      + (board.isVEdgeSet(r, c + 1) ? 1 : 0);
            if (sides == 2) count++;
        }
        if (r > 0 && c < cols) {
            int sides = (board.isHEdgeSet(r - 1, c) ? 1 : 0)
                      + (board.isVEdgeSet(r - 1, c) ? 1 : 0)
                      + (board.isVEdgeSet(r - 1, c + 1) ? 1 : 0);
            if (sides == 2) count++;
        }
        return count;
    }

    // Compte combien de cases cette arête verticale (r,c) fermerait
    private static int countClosingV(Board board, int r, int c, int rows, int cols) {
        int count = 0;
        // Case à droite (r, c)
        if (r < rows && c < cols
                && board.isHEdgeSet(r, c) && board.isHEdgeSet(r + 1, c) && board.isVEdgeSet(r, c + 1))
            count++;
        // Case à gauche (r, c-1)
        if (r < rows && c > 0
                && board.isHEdgeSet(r, c - 1) && board.isHEdgeSet(r + 1, c - 1) && board.isVEdgeSet(r, c - 1))
            count++;
        return count;
    }

    // Compte combien de cases cette arête verticale (r,c) "donnerait"
    private static int countGivingV(Board board, int r, int c, int rows, int cols) {
        int count = 0;
        if (r < rows && c < cols) {
            int sides = (board.isHEdgeSet(r, c) ? 1 : 0)
                      + (board.isHEdgeSet(r + 1, c) ? 1 : 0)
                      + (board.isVEdgeSet(r, c + 1) ? 1 : 0);
            if (sides == 2) count++;
        }
        if (r < rows && c > 0) {
            int sides = (board.isHEdgeSet(r, c - 1) ? 1 : 0)
                      + (board.isHEdgeSet(r + 1, c - 1) ? 1 : 0)
                      + (board.isVEdgeSet(r, c - 1) ? 1 : 0);
            if (sides == 2) count++;
        }
        return count;
    }

    // =========================================================================
    // COMPTAGE DES ARÊTES JOUÉES — sans allouer de liste
    // =========================================================================

    private static int countPlayedEdges(Board board, int rows, int cols) {
        int count = 0;
        for (int r = 0; r < board.getRows(); r++) {
            for (int c = 0; c < cols; c++) {
                if (board.isHEdgeSet(r, c)) count++;
            }
        }
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < board.getCols(); c++) {
                if (board.isVEdgeSet(r, c)) count++;
            }
        }
        return count;
    }

    // =========================================================================
    // COMPTAGE DES COUPS SÛRS — sans allouer de liste
    // =========================================================================

    private static int countSafeMoves(Board board, int rows, int cols) {
        int safe = 0;
        // Arêtes horizontales
        for (int r = 0; r < board.getRows(); r++) {
            for (int c = 0; c < cols; c++) {
                if (board.isHEdgeSet(r, c)) continue;
                if (countClosingH(board, r, c, rows, cols) == 0
                        && countGivingH(board, r, c, rows, cols) == 0) {
                    safe++;
                }
            }
        }
        // Arêtes verticales
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < board.getCols(); c++) {
                if (board.isVEdgeSet(r, c)) continue;
                if (countClosingV(board, r, c, rows, cols) == 0
                        && countGivingV(board, r, c, rows, cols) == 0) {
                    safe++;
                }
            }
        }
        return safe;
    }

    // =========================================================================
    // UNION-FIND (inline, sans allocation)
    // =========================================================================

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]]; // path compression
            x = parent[x];
        }
        return x;
    }

    private static void union(int[] parent, int[] rank, int[] compSize,
                               boolean[] compHasThree, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra == rb) return;

        // Union par rang
        if (rank[ra] < rank[rb]) { int tmp = ra; ra = rb; rb = tmp; }
        parent[rb] = ra;
        if (rank[ra] == rank[rb]) rank[ra]++;

        compSize[ra] += compSize[rb];
        compHasThree[ra] |= compHasThree[rb];
    }

    // =========================================================================
    // UTILITAIRE
    // =========================================================================

    private static int countEdges(Board board, int r, int c) {
        int count = 0;
        if (board.isHEdgeSet(r, c)) count++;       // top
        if (board.isHEdgeSet(r + 1, c)) count++;   // bottom
        if (board.isVEdgeSet(r, c)) count++;       // left
        if (board.isVEdgeSet(r, c + 1)) count++;   // right
        return count;
    }
}
