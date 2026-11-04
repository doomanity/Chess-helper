# chess helper

android app: watches the chess board on your screen, asks stockfish for the best move, and highlights it on top of your chess app.

the board is tracked on the phone itself, a few times a second: it finds the board, sees which squares have white or black pieces, learns what each piece looks like from the starting position, and follows the game with the rules of chess. hint dots, highlights and check frames are ignored. claude haiku is only used once when you start from a position that isn't the normal start (or if the app gets completely lost), so it costs almost nothing.

two engines: stockfish (on the phone, adjustable difficulty) or maia3 (plays like a human of the rating you pick, runs on your pc, see below).

## get the apk

github builds it for you. open the Actions tab and wait for the build (about 5 to 10 minutes), then download the apk from Releases ("latest build") and install it (allow "install unknown apps" for your browser).

## use it

1. open the app, paste your claude api key, pick your side and difficulty
2. tap "1. allow draw over other apps" and turn it on
3. tap "2. start" and allow screen capture
4. open your chess app. after a few seconds it shows 1 to 4 moves (you pick how many in the app) as arrows with a small list: dark green = best, light green = decent, yellow = acceptable, red with a skull = blunder. the numbers are stockfish's evaluation at full power, for both engines
5. stop or pause it from the app, the quick menu or the notification

## extras

- **guess the bot's move**: while the bot thinks, a faint dashed arrow with a "?" shows its likely move (maia3 at the bot's rating if you use maia3, else stockfish). your answer to that move is worked out early, so if the guess is right your moves show up at once ("ready early" on the card)
- **live accuracy**: "you 87% · bot 72%" on the card, lichess style. each move is scored by how much it dropped the mover's winning chances. moves the app missed (two moves at once) are skipped
- **move labels**: every move that was just played (yours and the bot's) gets a small badge on the square it went to: brilliant, great, best, excellent, good, book, inaccuracy, mistake, miss or blunder. brilliant and great glow blue with a shimmer. it is analysis only and never changes which moves are suggested. each label costs about half a second of stockfish. the "move labels" switch turns it off, and the rating used to judge comes from the review screen
- **premove**: while the bot thinks, your answer to the move the helper guesses (maia3's top pick when maia3 is on) is drawn next to the guess as a dashed arrow with a "P". if the bot plays the guess, your real suggestion shows at once. if it plays something else, the premove just goes away. the "premove" switch turns it off
- **pause**: hides everything and stops reading the screen until you resume

## game review

labels every move of a game like chess.com does. it is analysis only: it looks at moves that were already played and never picks or changes a move.

open it from the app ("open game review") or from the side bar ("review this game"). two ways to give it a game:

- **live game**: the game the helper is following. the helper saves every move it sees, and the review keeps up while you play (it checks for new moves every second or so)
- **paste pgn**: any pgn, or just a list of moves like `1. e4 e5 2. Nf3`. comments, variations and a FEN header are fine

labels: brilliant `!!`, great `!`, best, excellent, good, book, inaccuracy `?!`, mistake `?`, miss, blunder `??`. every position is looked at by the phone's stockfish at full power, then:

- best / excellent / good / inaccuracy / mistake / blunder = how many "expected points" the move gave away compared to the best move, with chess.com's published cut-offs (0.02, 0.05, 0.10, 0.20)
- **brilliant** = a good piece sacrifice: it gives up material that doesn't come back, you stay at least equal, it is (nearly) the best move, and you were not already winning by a lot
- **great** = the only move that wins (equal to winning) or saves (losing to equal), and not a plain recapture
- **book** = still inside known opening theory (about 8000 positions, from lichess's open chess-openings data). the opening name shows under the move
- **miss** = a small slip right after the opponent messed up, when there was something to take
- the rating picker (600 to 2500) bends the curve like chess.com does: lower ratings are judged more kindly. brilliant, great and miss have no published formula, so they are close copies of how chess.com describes them, not exact

a brilliant or great move glows on the board (bright blue and deep blue, with a shimmer over the piece and little squares floating up). there is also an accuracy score for both sides, a count of every label, an eval graph (tap or drag it to jump to a move) and a list of all the moves. "depth" picks how long stockfish thinks per position (fast / normal / deep).

the opening book is `app/src/main/assets/openings.tsv`. rebuild it with `tools/build_openings.py` (see the top of that file).

## side bar (quick menu while playing)

touch the right (or left) edge of the screen: a thin glowing bar shows up. slide it towards the middle and the quick menu opens: your side, engine, strength, moves shown, eval bar / threat / best line, arrow style, effects, theme, rethink, max power (stockfish at full power for one move, a few saves per game) and new game. drag the bar up or down to move it. it hides itself when you don't touch it.

## maia3 (optional, runs on your pc)

on your pc (phone and pc on the same wifi):

    pip install torch
    pip install git+https://github.com/CSSLab/maia3.git
    maia3-cache --model 23m
    python tools/maia3_server.py

it prints an address like 192.168.1.20:8765. in the app pick "maia3 (on my pc)" and type that address, the model and the rating. if the pc can't be reached the app falls back to stockfish for that move.

**human-ness** (0 to 100%): 0 = always maia's most likely move (strongest). higher = it picks moves the way a real human of that rating would, randomness and mistakes included (maia3's temperature, 100% = 1.0).

**away from home**: install tailscale on the pc and the phone with the same account, then use the pc's 100.x.x.x tailscale address instead. don't open the port on your router, the server has no password.

## if the build fails

open the failed run in the Actions tab, copy the red error text and send it to claude.

## tests

selftest/SelfTest.kt checks the board reading and game following on a real screenshot and on simulated games (both sides, flipped board, castling, en passant, promotion, mid-game start). it runs on a computer, not in the app.

selftest/ReviewTest.kt checks the move labels (brilliant, great, miss, the thresholds, the opening hash, pgn text reading) with made-up engine numbers. it needs no phone, no engine and no chess library: compile it with app/src/main/java/com/example/chesshelper/{Review,Accuracy,Candidates}.kt and run `ReviewTest`.
