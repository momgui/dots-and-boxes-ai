# dots-and-boxes-ai

Deux agents d'IA pour le jeu **Dots and Boxes** (La Pipopipette), développés pour le tournoi du cours d'**IA symbolique** (L3, Université Paris-Saclay, 2026) :

1. **Alpha-Beta + Table de transposition** (`src/alphabeta/`) — l'agent aligné en tournoi
2. **MCTS parallélisé** (`src/mcts/`) — variante Monte-Carlo développée en parallèle pour comparaison

> ⚠️ Ce repo contient uniquement **ma stratégie** — le moteur de jeu (`Board`, `Action`, arbitre, infrastructure de tournoi) est fourni par l'équipe enseignante et n'est pas redistribué ici. Le code ne compile donc pas de façon autonome : il est publié comme **étude de cas d'implémentation** d'algorithmes de recherche adversariale.

## Pourquoi Dots and Boxes est un jeu intéressant pour l'IA

Sous ses airs de jeu d'enfant, Dots and Boxes a deux propriétés qui piègent les algorithmes naïfs :

- **Le tour ne alterne pas toujours** : fermer une case fait rejouer immédiatement. Le minimax standard "MAX puis MIN puis MAX…" est faux — il faut décider à chaque nœud qui joue le prochain coup.
- **La fin de partie est combinatoire** : le jeu se décide sur le contrôle des *chaînes* et des *boucles*. La technique du **double-dealing** (sacrifier volontairement 2 cases pour garder la main) est contre-intuitive : l'agent glouton qui prend tout ce qu'il peut perd contre un agent qui sait donner.

## Agent principal : Alpha-Beta + Table de transposition

### Vue d'ensemble

```
selectAction(board)
   │
   ├── budget temps global de la partie, alloué dynamiquement par coup
   │
   └── Iterative Deepening (profondeur 1, 2, 3, … jusqu'au timeout)
          │
          └── Alpha-Beta avec :
                ├── PVS (Principal Variation Search, fenêtres nulles)
                ├── Table de transposition (Zobrist 64 bits, flags EXACT/LOWER/UPPER)
                ├── Move ordering (TT best-move en tête + tri par catégorie)
                └── Évaluation : heuristique chaînes/boucles à budget zéro-allocation
```

### Les briques, une par une

**Hachage de Zobrist** (`ZobristHash.java`) — chaque arête (horizontale/verticale) reçoit un nombre aléatoire 64 bits fixe ; le hash d'un plateau est le XOR des arêtes jouées. Le joueur au trait et le différentiel de score sont mélangés au hash (constante de Weyl `0x9E3779B97F4A7C15`), car deux plateaux identiques avec des scores différents ne sont **pas** la même position.

**Table de transposition** (`transpositionv2.java`, `TTEntry.java`) — mémorise `(hash → valeur, profondeur, flag, meilleur coup)`. Les flags distinguent une valeur exacte d'une borne (coupure alpha ou beta), ce qui permet de resserrer `[alpha, beta]` même quand la valeur exacte n'est pas connue. Le meilleur coup mémorisé sert au move ordering du prochain passage — c'est là que l'iterative deepening devient rentable : la recherche à profondeur *n* pré-trie les coups de la recherche à profondeur *n+1*.

**PVS / fenêtres nulles** — après le premier coup (supposé meilleur grâce au move ordering), les suivants sont d'abord testés avec une fenêtre `[alpha, alpha+1]` : il est moins coûteux de *prouver qu'un coup est moins bon* que de calculer sa valeur exacte. Si le test échoue (le coup est en fait meilleur), on relance une recherche complète.

**Move ordering par connaissance du domaine** — les coups sont triés en 5 catégories avant exploration :

| Priorité | Catégorie | Intuition |
|---|---|---|
| 1 | Ferme 2 cases | Gain immédiat maximal |
| 2 | Ferme 1 case | Gain immédiat |
| 3 | Neutre | Coup sûr |
| 4 | Offre 1 case | Sacrifice possible (double-dealing) |
| 5 | Offre 2 cases | À éviter sauf nécessité |

**Gestion du tour non-alternant** — quand un coup ferme une case, le même joueur rejoue **et la profondeur n'est pas décrémentée** : une rafale de captures forcées est traitée comme un seul "coup" logique, ce qui évite l'explosion artificielle de la profondeur en fin de partie.

**Budget temps de niveau tournoi** — le règlement impose un budget global par partie. L'agent le répartit dynamiquement : `temps_du_coup = temps_restant / (coups_disponibles/4 + 1)`, borné à [50 ms, 15 s]. Début de partie rapide, milieu de partie approfondi.

**`undo()` par réflexion** — le moteur fourni ne proposait pas d'annulation de coup, et copier le plateau à chaque nœud aurait dominé le coût de la recherche. Solution : un `undo` maison qui restaure les arêtes via l'API publique et « casse » l'encapsulation du tableau privé `boxes` par réflexion Java pour restaurer les propriétaires de cases. Pas élégant, mais des ordres de grandeur plus rapide que la copie défensive.

### Heuristique d'évaluation (`heuristicv2.java`)

Appelée à chaque feuille, donc conçue **zéro allocation superflue, un seul parcours** :

- **Phase de jeu** estimée par le ratio d'arêtes jouées — les poids de chaque terme s'adaptent (le score brut pèse de plus en plus lourd vers la fin)
- **Analyse de chaînes et boucles par Union-Find** : les cases à 2+ côtés connectées entre elles forment les chaînes/boucles qui décident la fin de partie. L'union-find (avec union par rang) les extrait en quasi-O(n) pendant le scan, sans exploration récursive séparée
- **Parité des longues chaînes** : la règle classique du jeu — le joueur qui contrôle la parité des longues chaînes gagne la fin de partie — est encodée dans le score
- **Anticipation du double-dealing** : les tailles de chaînes capturables sont comparées pour savoir si sacrifier 2 cases préserve le contrôle

## Agent alternatif : MCTS parallélisé (`src/mcts/`)

Monte-Carlo Tree Search avec **root parallelization** : chaque thread du pool (un par cœur) construit son propre arbre UCT indépendant pendant le budget temps, puis les statistiques des racines sont fusionnées pour élire le coup le plus visité.

- Sélection **UCT** (`c = 1.0`), expansion, simulation aléatoire, rétropropagation
- Racines indépendantes → aucun verrou pendant la recherche, scalabilité linéaire en threads
- Fusion des statistiques `(visites, victoires)` par action à la racine

**Verdict expérimental** : sur ce jeu, l'Alpha-Beta domine — les simulations aléatoires du MCTS évaluent très mal les fins de partie en chaînes (un rollout aléatoire fait n'importe quoi face au double-dealing), alors que l'heuristique dédiée capture précisément cette structure. C'est un bon exemple de la limite du MCTS *vanilla* sur les jeux à structure combinatoire forte, là où il excelle sur les jeux à évaluation difficile (Go).

## Structure

```
src/
├── alphabeta/
│   ├── AlphaBetaTTStrategy.java   # Recherche : ID + PVS + TT + budget temps
│   ├── heuristicv2.java           # Évaluation : phases, union-find, chaînes/boucles
│   ├── ZobristHash.java           # Hachage incrémental 64 bits
│   ├── transpositionv2.java       # Table de transposition
│   ├── TTEntry.java               # Entrée de TT (valeur, profondeur, flag, best move)
│   └── TimeOutException.java      # Interruption propre de la recherche
└── mcts/
    └── ParallelMCTSStrategy.java  # MCTS root-parallélisé (UCT + thread pool)
```

## Ce que j'ai appris

- L'essentiel du gain ne vient pas de l'algorithme de base mais de **l'empilement des optimisations** : move ordering × TT × PVS × iterative deepening se renforcent mutuellement (le PVS n'est rentable que si le move ordering est bon, qui n'est bon que grâce à la TT…)
- **La connaissance du domaine bat la puissance de calcul** : l'heuristique chaînes/boucles a plus fait progresser l'agent que n'importe quel gain de vitesse
- Mesurer avant d'optimiser : le passage copie-de-plateau → undo par réflexion a été le plus gros gain de performance brut

## Contexte

Tournoi d'agents du cours d'IA symbolique, L3 Math-Info, Université Paris-Saclay (avril 2026). Le moteur de jeu et l'infrastructure de tournoi sont la propriété de l'équipe enseignante.
