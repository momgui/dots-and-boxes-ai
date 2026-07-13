package dotsandboxes.alphabeta;

import java.lang.reflect.Field;
import DotsBoxes.board.Action;
import DotsBoxes.board.Board;
import DotsBoxes.player.ActionStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Stratégie Alpha-Beta avec Table de Transposition (Zobrist) et Move Ordering.
 * L'idée est d'accélérer la recherche en mémorisant les plateaux déjà vus.
 */
public class AlphaBetaTTStrategy implements ActionStrategy {

    // Composants pour les stats et la mémoire
    private final transpositionv2 tableTransposition = new transpositionv2();

    // Gestion du Hash Zobrist
    private ZobristHash zobrist;
    private int zobristRows = -1;
    private int zobristCols = -1;

    // Variables pour gérer le chrono sur toute la durée de la partie (tournoi)
    private long tempsConsommeTotal = 0;
    private long budgetTotalMs = -1;

    // Pour savoir si on vient de commencer une nouvelle partie
    private int aretesDernierCoup = -1;

    private int derniereProfondeur = 0;
    private int dernierScoreCalcule = 0;

    /**
     * Initialisation de l'IA avec les outils de stats pour le débug.
     */
    public AlphaBetaTTStrategy() {}

    // Méthode pour vérifier si on dépasse le temps alloué
    private void verifierTemps(long deadline) {
        if (System.currentTimeMillis() >= deadline) {
            throw new TimeOutException();
        }
    }

    /**
     * Trie les actions pour tester le meilleur coup connu en premier (Move ordering).
     * Permet de faire plus de coupures Alpha-Beta.
     */
    private List<Action> reordonnerAvecTT(List<Action> actions, long hash) {
        TTEntry entree = tableTransposition.lookup(hash);

        if (entree == null || entree.bestAction == null || !actions.contains(entree.bestAction)) {
            return actions;
        }

        // On place le bestAction en tête s'il est dans la liste
        Action meilleurConnu = entree.bestAction;
        List<Action> reordonnees = new ArrayList<>(actions.size());
        reordonnees.add(meilleurConnu);

        for (Action action : actions) {
            if (!action.equals(meilleurConnu)) {
                reordonnees.add(action);
            }
        }

        return reordonnees;
    }

    // L'algorithme Alpha-Beta principal avec la gestion de la table de transposition
    public int alphaBetaTT(Board board, int depth, int joueurCourant,
                           int joueurPrincipal, int score,
                           int alpha, int beta, long deadline) {

        verifierTemps(deadline);
        if (this.zobrist == null) {
            this.zobrist = new ZobristHash(board.getRows(), board.getCols());
        }

        // On regarde si on a déjà ce plateau en mémoire
        long hash = zobrist.computeWithPlayer(board, joueurCourant);
        hash ^= ((long) score * 0x9E3779B97F4A7C15L);
        TTEntry entree = tableTransposition.lookup(hash);

        if (entree != null && entree.depth >= depth) {
            switch (entree.flag) {
                case EXACT:
                    // Valeur exacte — on peut la retourner directement
                    return entree.value;

                case LOWER_BOUND:
                    // Vraie valeur ≥ entree.value — on élève alpha
                    alpha = Math.max(alpha, entree.value);
                    break;

                case UPPER_BOUND:
                    // Vraie valeur ≤ entree.value — on abaisse beta
                    beta = Math.min(beta, entree.value);
                    break;
            }

            // Coupure possible après mise à jour des bornes
            if (alpha >= beta) {
                return entree.value;
            }
        }


        // Cas de base (fin de partie ou profondeur max atteinte)
        List<Action> actions = getSortedActions(board);

        if (actions.isEmpty()) {
            if (score > 0) return 1_000_000 + score;
            if (score < 0) return -1_000_000 + score;
            return 0;
        }

        if (depth == 0) {
            return heuristicv2.evaluate(board, joueurPrincipal, joueurCourant);
        }

        // Move Ordering pour optimiser l'élagage
        actions = reordonnerAvecTT(actions, hash);

        // Boucle Alpha-Beta classique (Minimax)
        boolean estMax   = (joueurPrincipal == joueurCourant);
        int alphaInitial = alpha;
        int betaInitiale = beta;
        int best;
        Action meilleurCoup = null;

        if (estMax) {
            best = Integer.MIN_VALUE;
            boolean premierCoup = true;

            for (Action action : actions) {
                int nbFerme    = board.apply(action, joueurCourant);
                int nouveauScore = score + nbFerme;
                int prochainJoueur = (nbFerme > 0) ? joueurCourant : 1 - joueurCourant;
                int prochaineProf  = (nbFerme > 0) ? depth : depth - 1;
                int res;

                try {
                    if (premierCoup) {
                        // Traitement normal pour le coup favori (souvent issu de la TT)
                        res = alphaBetaTT(board, prochaineProf, prochainJoueur,
                                joueurPrincipal, nouveauScore, alpha, beta, deadline);
                        premierCoup = false;
                    } else {
                        // PVS : Fenêtre nulle [alpha, alpha + 1] pour prouver que le coup est moins bon
                        res = alphaBetaTT(board, prochaineProf, prochainJoueur,
                                joueurPrincipal, nouveauScore, alpha, alpha + 1, deadline);

                        // Si le score "casse" la fenêtre, on doit faire une recherche complète
                        if (res > alpha && res < beta) {
                            res = alphaBetaTT(board, prochaineProf, prochainJoueur,
                                    joueurPrincipal, nouveauScore, alpha, beta, deadline);
                        }
                    }
                } finally {
                    undo(board,action);
                }

                if (res > best) {
                    best        = res;
                    meilleurCoup = action;
                }

                alpha = Math.max(alpha, best);

                if (alpha >= beta) {
                    break;
                }
            }

        } else {
            best = Integer.MAX_VALUE;
            boolean premierCoup = true;

            for (Action action : actions) {
                int nbFerme    = board.apply(action, joueurCourant);
                int nouveauScore = score - nbFerme;
                int prochainJoueur = (nbFerme > 0) ? joueurCourant : 1 - joueurCourant;
                int prochaineProf  = (nbFerme > 0) ? depth : depth - 1;
                int res;

                try {
                    if (premierCoup) {
                        res = alphaBetaTT(board, prochaineProf, prochainJoueur,
                                joueurPrincipal, nouveauScore, alpha, beta, deadline);
                        premierCoup = false;
                    } else {
                        // PVS côté MIN : Fenêtre nulle autour de beta [beta - 1, beta]
                        res = alphaBetaTT(board, prochaineProf, prochainJoueur,
                                joueurPrincipal, nouveauScore, beta - 1, beta, deadline);

                        // Si l'adversaire trouve une faille, on relance la recherche complète
                        if (res < beta && res > alpha) {
                            res = alphaBetaTT(board, prochaineProf, prochainJoueur,
                                    joueurPrincipal, nouveauScore, alpha, beta, deadline);
                        }
                    }
                } finally {
                    undo(board,action);
                }

                if (res < best) {
                    best        = res;
                    meilleurCoup = action;
                }

                beta = Math.min(beta, best);

                if (alpha >= beta) {
                    break;
                }
            }
        }

        // On enregistre le résultat dans la table avant de remonter
        TTEntry.Flag flag;
        if (best <= alphaInitial) {
            // Coupure alpha : la vraie valeur est ≤ best
            flag = TTEntry.Flag.UPPER_BOUND;
        } else if (best >= betaInitiale) {
            // Coupure beta : la vraie valeur est ≥ best
            flag = TTEntry.Flag.LOWER_BOUND;
        } else {
            // Aucune coupure : valeur exacte
            flag = TTEntry.Flag.EXACT;
        }

        tableTransposition.store(hash, best, depth, flag, meilleurCoup);

        return best;
    }

    // Lance une recherche à une profondeur précise
    public Action rechercherMeilleurCoupAProfondeur(Board board, int profondeur,
                                                    List<Action> actionsDisponibles,
                                                    int joueurPrincipal, long deadline) {

        int scoreInitial = board.getScore(joueurPrincipal) - board.getScore(1 - joueurPrincipal);

        Action meilleurCoupChoisi = null;
        int valeurMax  = Integer.MIN_VALUE;
        int alpha      = Integer.MIN_VALUE;
        int beta       = Integer.MAX_VALUE;
        boolean premierCoup = true;

        for (Action action : actionsDisponibles) {
            int nbFerme        = board.apply(action, joueurPrincipal);
            int nouveauScore   = scoreInitial + nbFerme;
            int prochainJoueur = (nbFerme > 0) ? joueurPrincipal : 1 - joueurPrincipal;
            int prochaineProf  = (nbFerme > 0) ? profondeur : profondeur - 1;
            int res;

            try {
                if (premierCoup) {
                    res = alphaBetaTT(board, prochaineProf, prochainJoueur,
                            joueurPrincipal, nouveauScore, alpha, beta, deadline);
                    premierCoup = false;
                } else {
                    // PVS à la racine
                    res = alphaBetaTT(board, prochaineProf, prochainJoueur,
                            joueurPrincipal, nouveauScore, alpha, alpha + 1, deadline);

                    if (res > alpha && res < beta) {
                        res = alphaBetaTT(board, prochaineProf, prochainJoueur,
                                joueurPrincipal, nouveauScore, alpha, beta, deadline);
                    }
                }
            } finally {
                undo(board,action);
            }

            // On n'accepte le coup que s'il est strictement meilleur
            if (res > valeurMax) {
                valeurMax = res;
                meilleurCoupChoisi = action;
                alpha = valeurMax;
            }
        }

        // Sécurité anti-crash
        if (meilleurCoupChoisi == null) {
            return actionsDisponibles.get(0);
        }
        this.dernierScoreCalcule = valeurMax;

        return meilleurCoupChoisi;
    }


    @Override
    public Action selectAction(Board board, int playerId) {
        long debut = System.currentTimeMillis();
        int aretesDispo = board.getAvailableActions().size();
        int totalEdges = board.getRows() * (board.getCols() - 1) + (board.getRows() - 1) * board.getCols();

        // Réinitialisation au début de chaque match
        if (aretesDispo > aretesDernierCoup) {
            budgetTotalMs = totalEdges * 1000L;
            tempsConsommeTotal = 0;
            tableTransposition.clear(); // On vide la mémoire de la partie précédente
        }

        // On met à jour pour le prochain tour
        aretesDernierCoup = aretesDispo;

        // Initialisation Zobrist
        if (zobrist == null || board.getRows() != zobristRows || board.getCols() != zobristCols) {
            zobrist = new ZobristHash(board.getRows(), board.getCols());
            zobristRows = board.getRows();
            zobristCols = board.getCols();
        }

        List<Action> actionsDisponibles = getSortedActions(board);
        if (actionsDisponibles.isEmpty()) return null;
        //Collections.shuffle(actionsDisponibles);

        // On calcule combien de temps on s'autorise pour ce coup
        long tempsRestant = budgetTotalMs - tempsConsommeTotal;
        long tempsAlloueCeCoup = tempsRestant / (actionsDisponibles.size() / 4 + 1);
        tempsAlloueCeCoup = Math.max(50, Math.min(15000, tempsAlloueCeCoup));
        long deadline = debut + tempsAlloueCeCoup;

        Action meilleurCoupTrouve = null;
        int profondeurCourante    = 1;

        try {
            // On cherche de plus en plus profond (Iterative Deepening)
            do {
                meilleurCoupTrouve = rechercherMeilleurCoupAProfondeur(
                        board, profondeurCourante, actionsDisponibles, playerId, deadline
                );
                derniereProfondeur = profondeurCourante;
                profondeurCourante++;

            } while (profondeurCourante <= actionsDisponibles.size());

        } catch (TimeOutException ignored) {}

        // Mise à jour de l'horloge
        tempsConsommeTotal += (System.currentTimeMillis() - debut);

        if (meilleurCoupTrouve == null) {
            // Aléatoire car shuffle avant de chercher meilleurCoupTrouve
            return actionsDisponibles.get(0);
        }

        tableTransposition.printStats();
        return meilleurCoupTrouve;
    }


    /**
     * @return true si une action ferme une boite false sinon
     */
    public boolean isActionClosing(Board board,Action action) {
        return countClosing(board,action) > 0;
    }

    public boolean isActionGiving(Board board,Action action) {
        return countGiving(board,action) > 0;
    }


    public List<Action> getSortedActions(Board board) {
        List<Action> actions = board.getAvailableActions();

        List<Action> closing2 = new ArrayList<>(); // ferme 2 cases → priorité absolue
        List<Action> closing1 = new ArrayList<>(); // ferme 1 case
        List<Action> neutral  = new ArrayList<>(); // aucun impact immédiat
        List<Action> giving1  = new ArrayList<>(); // offre 1 case à l'adversaire
        List<Action> giving2  = new ArrayList<>(); // offre 2 cases → à éviter absolument

        for (Action action : actions) {
            int closes = countClosing(board, action); // 0, 1 ou 2
            int gives  = countGiving(board, action);  // 0, 1 ou 2

            if      (closes == 2) closing2.add(action);
            else if (closes == 1) closing1.add(action);
            else if (gives  == 2) giving2.add(action);
            else if (gives  == 1) giving1.add(action);
            else                  neutral.add(action);
        }

        List<Action> sorted = new ArrayList<>();
        sorted.addAll(closing2);
        sorted.addAll(closing1);
        sorted.addAll(neutral);
        sorted.addAll(giving1);
        sorted.addAll(giving2);
        return sorted;
    }

    public int countClosing(Board board, Action action) {
        int count = 0;
        int r = action.getRow();
        int c = action.getCol();

        if (action.getType() == Action.Type.HORIZONTAL) {
            if (r < board.getRows() - 1 && c < board.getCols() - 1
                    && board.getHEdges()[r+1][c] && board.getVEdges()[r][c] && board.getVEdges()[r][c+1]) count++;
            if (r > 0 && c < board.getCols() - 1
                    && board.getHEdges()[r-1][c] && board.getVEdges()[r-1][c] && board.getVEdges()[r-1][c+1]) count++;
        } else {
            if (r < board.getRows() - 1 && c < board.getCols() - 1
                    && board.getHEdges()[r][c] && board.getHEdges()[r+1][c] && board.getVEdges()[r][c+1]) count++;
            if (r < board.getRows() - 1 && c > 0
                    && board.getHEdges()[r][c-1] && board.getHEdges()[r+1][c-1] && board.getVEdges()[r][c-1]) count++;
        }
        return count; // 0, 1 ou 2
    }

    public int countGiving(Board board,Action action) {
        int count = 0;
        int r = action.getRow();
        int c = action.getCol();

        if (action.getType() == Action.Type.HORIZONTAL) {
            if (r < board.getRows() - 1 && c < board.getCols() - 1) {
                int sides = (board.getHEdges()[r+1][c] ? 1 : 0) + (board.getVEdges()[r][c] ? 1 : 0) + (board.getVEdges()[r][c+1] ? 1 : 0);
                if (sides == 2) count++;
            }
            if (r > 0 && c < board.getCols() - 1) {
                int sides = (board.getHEdges()[r-1][c] ? 1 : 0) + (board.getVEdges()[r-1][c] ? 1 : 0) + (board.getVEdges()[r-1][c+1] ? 1 : 0);
                if (sides == 2) count++;
            }
        } else {
            if (r < board.getRows() - 1 && c < board.getCols() - 1) {
                int sides = (board.getHEdges()[r][c] ? 1 : 0) + (board.getHEdges()[r+1][c] ? 1 : 0) + (board.getVEdges()[r][c+1] ? 1 : 0);
                if (sides == 2) count++;
            }
            if (r < board.getRows() - 1 && c > 0) {
                int sides = (board.getHEdges()[r][c-1] ? 1 : 0) + (board.getHEdges()[r+1][c-1] ? 1 : 0) + (board.getVEdges()[r][c-1] ? 1 : 0);
                if (sides == 2) count++;
            }
        }
        return count; // 0, 1 ou 2
    }

    // 1. Fonction utilitaire pour "craquer" l'encapsulation de la classe Board
    private int[][] getPrivateBoxes(Board board) {
        try {
            // Remplace "boxes" par le vrai nom de la variable privée dans la classe Board si besoin
            Field field = board.getClass().getDeclaredField("boxes");
            field.setAccessible(true); // Fait sauter la protection private
            return (int[][]) field.get(board);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // 2. Ta fonction undo corrigée
    public void undo(Board board, Action action) {
        int r = action.getRow();
        int c = action.getCol();

        // On récupère la référence du tableau privé
        int[][] privateBoxes = getPrivateBoxes(board);

        if (action.getType() == Action.Type.HORIZONTAL) {
            board.getHEdges()[r][c] = false;

            if (r > 0 && board.getBoxOwner(r-1, c) != -1) {
                privateBoxes[r - 1][c] = -1;
            }
            if (r < board.getRows() - 1 && board.getBoxOwner(r, c) != -1) {
                privateBoxes[r][c] = -1;
            }
        } else { // VERTICAL
            board.getVEdges()[r][c] = false;

            if (c > 0 && board.getBoxOwner(r, c-1) != -1) {
                privateBoxes[r][c - 1] = -1;
            }
            if (c < board.getCols() - 1 && board.getBoxOwner(r, c) != -1) {
                privateBoxes[r][c] = -1;
            }
        }

    }

    // Getters
    public transpositionv2 getTableTransposition() {
        return tableTransposition;
    }

    @Override
    public String getName() {
        return "Alpha-Beta + Table de Transposition";
    }

    public int getProf() {
        return derniereProfondeur;
    }

    public int getDernierScoreCalcule() {
        return dernierScoreCalcule;
    }
}
