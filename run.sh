#!/usr/bin/env bash
# Build with plain javac, then play.
#   ./run.sh                 alpha-beta vs Greedy, 2 games (about 1.5 min)
#   ./run.sh bench           full benchmark -> docs/benchmark.txt and docs/final-board.png (about 20 min)
#   ./run.sh A B [options]   any match-up, see src/arena/Arena.java for the options
set -euo pipefail
cd "$(dirname "$0")"

rm -rf out
mkdir -p out
find src -name '*.java' > out/sources.txt
javac -encoding UTF-8 -d out @out/sources.txt

JAVA=(java -Xmx4g -cp out arena.Arena)

case "${1:-demo}" in
  demo)
    "${JAVA[@]}" alphabeta greedy --games 2
    ;;
  bench)
    mkdir -p docs
    {
      echo "# Benchmark, run with ./run.sh bench on $(date '+%Y-%m-%d')"
      echo "# $(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m), $(java -version 2>&1 | head -1)"
      echo
      "${JAVA[@]}" alphabeta random --games 20 --parallel 4
      "${JAVA[@]}" alphabeta greedy --games 20 --parallel 4 --png docs/final-board.png
      "${JAVA[@]}" alphabeta mcts   --games 10 --mcts-ms 250
      "${JAVA[@]}" mcts      greedy --games 40 --mcts-ms 250
    } 2>&1 | tee docs/benchmark.txt
    ;;
  *)
    "${JAVA[@]}" "$@"
    ;;
esac
