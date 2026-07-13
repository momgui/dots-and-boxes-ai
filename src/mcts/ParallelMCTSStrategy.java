package dotsandboxes.mcts;

import DotsBoxes.board.Action;
import DotsBoxes.board.Board;
import DotsBoxes.player.ActionStrategy;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Version etudiants.
 * TODO:
 * - Concevoir une strategie experte (heuristique + ordre de coups).
 * - Optionnel: utiliser une table de transposition.
 */
public class ParallelMCTSStrategy implements ActionStrategy {
    private final int iterations;
    private final long timeBudget;
    private final int numThreads;
    private final ExecutorService executor;

    private static final double CONSTANTE_EXPLORATION = 1.0;

    private final AtomicInteger totalSimulations = new AtomicInteger(0);
    private int dernierTotalSimulations = 0;
    private final Map<Action, Double[]> dernieresStatistiques = new HashMap<>();



    public ParallelMCTSStrategy(long timeBudget) {
        this.iterations = -1;
        this.timeBudget = timeBudget;
        this.numThreads = Runtime.getRuntime().availableProcessors();
        this.executor   = Executors.newFixedThreadPool(numThreads);
    }

    @Override
    public Action selectAction(Board board, int playerId) {
        totalSimulations.set(0);
        long debut = System.currentTimeMillis();

        List<Callable<ParallelMCTSStrategy.NoeudMCTS>> taches = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            taches.add(() -> {
                ParallelMCTSStrategy.NoeudMCTS racine = new ParallelMCTSStrategy.NoeudMCTS(null, null, playerId, board.getAvailableActions());
                ThreadLocalRandom random = ThreadLocalRandom.current();

                if (timeBudget > 0) {
                    long fin = System.currentTimeMillis() + timeBudget;
                    while (System.currentTimeMillis() < fin) {
                        executerIteration(racine, board, random);
                    }
                } else {
                    int parThread = iterations / numThreads;
                    for (int j = 0; j < parThread; j++) {
                        executerIteration(racine, board, random);
                    }
                }
                return racine;
            });
        }

        try {
            List<Future<ParallelMCTSStrategy.NoeudMCTS>> resultats = executor.invokeAll(taches);
            Action meilleur = fusionnerEtExtraireStats(resultats);

            long duree = System.currentTimeMillis() - debut;
            System.out.printf("MCTS: %d simulations en %d ms (%.0f sims/s)%n",
                    totalSimulations.get(), duree, (totalSimulations.get() * 1000.0 / duree));

            return meilleur;

        } catch (Exception e) {
            e.printStackTrace();
            return board.getAvailableActions().get(0);
        }
    }

    private void executerIteration(ParallelMCTSStrategy.NoeudMCTS racine, Board original, ThreadLocalRandom random) {
        Board plateau = new Board(original);

        ParallelMCTSStrategy.NoeudMCTS noeud = selectionner(racine, plateau);

        if (!plateau.isFinished()) {
            noeud = developper(noeud, plateau);
        }

        int gagnant = simuler(plateau, noeud.joueurCourant, random);

        retropropager(noeud, gagnant);

        totalSimulations.incrementAndGet();
    }

    private ParallelMCTSStrategy.NoeudMCTS selectionner(ParallelMCTSStrategy.NoeudMCTS noeud, Board plateau) {
        while (!plateau.isFinished() && noeud.actionsNonExplorees.isEmpty() && !noeud.enfants.isEmpty()) {
            noeud = noeud.meilleurUCT();
            plateau.apply(noeud.actionDepuisParent, noeud.parent.joueurCourant);
        }
        return noeud;
    }

    private ParallelMCTSStrategy.NoeudMCTS developper(ParallelMCTSStrategy.NoeudMCTS noeud, Board plateau) {
        Action action = noeud.actionsNonExplorees.remove(noeud.actionsNonExplorees.size() - 1);

        int casesFermees = plateau.apply(action, noeud.joueurCourant);
        int joueurSuivant = (casesFermees > 0) ? noeud.joueurCourant : 1 - noeud.joueurCourant;

        List<Action> legalActions = plateau.getAvailableActions();
        ParallelMCTSStrategy.NoeudMCTS enfant = new ParallelMCTSStrategy.NoeudMCTS(noeud, action, joueurSuivant, legalActions);

        // --- PRIOR KNOWLEDGE (Heuristic Bias) ---
        // Evaluate state for the parent's player to see if this child move was good
        int hScore = plateau.getScore(noeud.joueurCourant);
        double probWin = 1.0 / (1.0 + Math.exp(-hScore / 3000.0));

        // Add 10 phantom visits to bias initial UCT exploration
        enfant.phantomVisits = 10.0;
        enfant.phantomWins = probWin * 10.0;

        noeud.enfants.add(enfant);

        return enfant;
    }

    private int simuler(Board plateau, int joueurCourant, ThreadLocalRandom random) {
        List<Action> actions = plateau.getAvailableActions();

        // Early win configuration: total possible boxes
        int maxCases = (plateau.getRows() - 1) * (plateau.getCols() - 1) / 2;

        while (!actions.isEmpty()) {
            // Early win detection
            if (plateau.getScore(0) > maxCases) return 0;
            if (plateau.getScore(1) > maxCases) return 1;

            Action actionChoisie = null;
            int chosenIdx = -1;

            // 1. Chercher une action qui ferme une case (closing)
            for (int i = 0; i < actions.size(); i++) {
                if (isActionClosing(plateau,actions.get(i))) {
                    actionChoisie = actions.get(i);
                    chosenIdx = i;
                    break;
                }
            }

            // 2. Sinon, chercher une action safe au hasard (non giving)
            if (actionChoisie == null) {
                int depart = random.nextInt(actions.size());
                for (int i = 0; i < actions.size(); i++) {
                    int idx = (depart + i) % actions.size();
                    if (!isActionGiving(plateau,actions.get(idx))) {
                        actionChoisie = actions.get(idx);
                        chosenIdx = idx;
                        break;
                    }
                }
            }

            // 3. Sinon, totalement aléatoire
            if (actionChoisie == null) {
                chosenIdx = random.nextInt(actions.size());
                actionChoisie = actions.get(chosenIdx);
            }

            // Swap-and-pop technique for zero-allocation
            actions.set(chosenIdx, actions.get(actions.size() - 1));
            actions.remove(actions.size() - 1);

            int ferme = plateau.apply(actionChoisie, joueurCourant);
            if (ferme == 0) joueurCourant = 1 - joueurCourant;
        }

        int scoreJ0 = plateau.getScore(0);
        int scoreJ1 = plateau.getScore(1);

        if (scoreJ0 > scoreJ1) return 0;
        if (scoreJ1 > scoreJ0) return 1;
        return -1;
    }

    private void retropropager(ParallelMCTSStrategy.NoeudMCTS noeud, int idGagnant) {
        while (noeud != null) {
            noeud.visites++;
            if (idGagnant == -1) {
                noeud.scoreVictoires += 0.5;
            } else if (noeud.parent != null && idGagnant == noeud.parent.joueurCourant) {
                noeud.scoreVictoires += 1.0;
            }
            noeud = noeud.parent;
        }
    }

    private Action fusionnerEtExtraireStats(List<Future<ParallelMCTSStrategy.NoeudMCTS>> resultats) throws Exception {
        Map<Action, Double> visites = new HashMap<>();
        Map<Action, Double> victoires = new HashMap<>();

        dernieresStatistiques.clear();
        int total = 0;

        for (Future<ParallelMCTSStrategy.NoeudMCTS> f : resultats) {
            ParallelMCTSStrategy.NoeudMCTS racine = f.get();
            for (ParallelMCTSStrategy.NoeudMCTS enfant : racine.enfants) {
                Action a = enfant.actionDepuisParent;
                visites.put(a,   visites.getOrDefault(a, 0.0)   + enfant.visites);
                victoires.put(a, victoires.getOrDefault(a, 0.0) + enfant.scoreVictoires);
                total += enfant.visites;
            }
        }

        this.dernierTotalSimulations = total;

        for (Action a : visites.keySet()) {
            double v  = visites.get(a);
            double wr = (victoires.get(a) / v) * 100.0;
            dernieresStatistiques.put(a, new Double[]{v, wr});
        }

        return visites.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .get().getKey();
    }

    private static class NoeudMCTS {
        final ParallelMCTSStrategy.NoeudMCTS parent;
        final Action           actionDepuisParent;
        final int              joueurCourant;
        final List<ParallelMCTSStrategy.NoeudMCTS>  enfants            = new ArrayList<>();
        final List<Action>     actionsNonExplorees;

        double visites        = 0;
        double scoreVictoires = 0;

        double phantomVisits  = 0;
        double phantomWins    = 0;

        NoeudMCTS(ParallelMCTSStrategy.NoeudMCTS parent, Action action, int joueur, List<Action> actions) {
            this.parent              = parent;
            this.actionDepuisParent  = action;
            this.joueurCourant       = joueur;
            this.actionsNonExplorees = new ArrayList<>(actions);
        }

        ParallelMCTSStrategy.NoeudMCTS meilleurUCT() {
            ParallelMCTSStrategy.NoeudMCTS meilleur    = null;
            double    meilleurScore = Double.NEGATIVE_INFINITY;

            for (ParallelMCTSStrategy.NoeudMCTS enfant : enfants) {
                double totalVisites = enfant.visites + enfant.phantomVisits;
                double tauxVictoire = (enfant.scoreVictoires + enfant.phantomWins) / totalVisites;
                double uct = tauxVictoire + CONSTANTE_EXPLORATION
                        * Math.sqrt(Math.log(visites) / totalVisites);

                if (uct > meilleurScore) {
                    meilleurScore = uct;
                    meilleur      = enfant;
                }
            }
            return meilleur;
        }
    }

    public int getDernierTotalSimulations() { return dernierTotalSimulations; }
    public Map<Action, Double[]> getDernieresStatistiques() { return dernieresStatistiques; }




    private static boolean isActionClosing(Board plateau, Action action) {
        return countClosing(plateau, action) > 0;
    }

    private static boolean isActionGiving(Board plateau, Action action) {
        return countGiving(plateau, action) > 0;
    }

    private static int countClosing(Board plateau, Action action) {
        int count = 0;
        int r = action.getRow();
        int c = action.getCol();
        int rows = plateau.getRows();
        int cols = plateau.getCols();
        boolean[][] hEdges = plateau.getHEdges();
        boolean[][] vEdges = plateau.getVEdges();

        if (action.getType() == Action.Type.HORIZONTAL) {
            if (r < rows - 1 && c < cols - 1
                    && hEdges[r+1][c] && vEdges[r][c] && vEdges[r][c+1]) count++;
            if (r > 0 && c < cols - 1
                    && hEdges[r-1][c] && vEdges[r-1][c] && vEdges[r-1][c+1]) count++;
        } else {
            if (r < rows - 1 && c < cols - 1
                    && hEdges[r][c] && hEdges[r+1][c] && vEdges[r][c+1]) count++;
            if (r < rows - 1 && c > 0
                    && hEdges[r][c-1] && hEdges[r+1][c-1] && vEdges[r][c-1]) count++;
        }
        return count; // 0, 1 ou 2
    }

    private static int countGiving(Board plateau, Action action) {
        int count = 0;
        int r = action.getRow();
        int c = action.getCol();
        int rows = plateau.getRows();
        int cols = plateau.getCols();
        boolean[][] hEdges = plateau.getHEdges();
        boolean[][] vEdges = plateau.getVEdges();

        if (action.getType() == Action.Type.HORIZONTAL) {
            if (r < rows - 1 && c < cols - 1) {
                int sides = (hEdges[r+1][c] ? 1 : 0) + (vEdges[r][c] ? 1 : 0) + (vEdges[r][c+1] ? 1 : 0);
                if (sides == 2) count++;
            }
            if (r > 0 && c < cols - 1) {
                int sides = (hEdges[r-1][c] ? 1 : 0) + (vEdges[r-1][c] ? 1 : 0) + (vEdges[r-1][c+1] ? 1 : 0);
                if (sides == 2) count++;
            }
        } else {
            if (r < rows - 1 && c < cols - 1) {
                int sides = (hEdges[r][c] ? 1 : 0) + (hEdges[r+1][c] ? 1 : 0) + (vEdges[r][c+1] ? 1 : 0);
                if (sides == 2) count++;
            }
            if (r < rows - 1 && c > 0) {
                int sides = (hEdges[r][c-1] ? 1 : 0) + (hEdges[r+1][c-1] ? 1 : 0) + (vEdges[r][c-1] ? 1 : 0);
                if (sides == 2) count++;
            }
        }
        return count; // 0, 1 ou 2
    }
    @Override
    public String getName() { return "MCTS Parallel"; }
}
