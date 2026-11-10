# chess helper

android app: watches the chess board on your screen, asks stockfish for the best move, and highlights it on top of your chess app.

the board is tracked on the phone itself, a few times a second: it finds the board, sees which squares have white or black pieces, learns what each piece looks like from the starting position, and follows the game with the rules of chess. hint dots, highlights and check frames are ignored. claude haiku is only used once when you start from a position that isn't the normal start (or if the app gets completely lost), so it costs almost nothing.

three engines: stockfish (on the phone, adjustable difficulty), maia3 (plays like a human of the rating you pick, runs on your pc, see below) or illusion (brody's own engine, github.com/doomanity/Illusion, built for the phone in the same github build).

## illusion

- **as your engine**: pick "illusion" in the engine picker. its pick is first on the card, tagged ✦ (or ⚑ when it is a trap), and stockfish fills the other moves. stockfish still grades every move, so the numbers always mean the same thing
- **human mode**: illusion's personality, one switch: it attacks the king, trades when ahead and keeps pieces when behind, avoids draws, hunts sound sacrifices and sets traps for a player of the "opponent rating". off = pure engine. sliders: aggression (0-100), contempt (0-200, how much it hates draws), swindle tolerance (0-300, the most a trap may cost vs the best move, 0 = no traps). on the card its pick is tagged ✦, ⚑ when it is a trap, !! when it is a real sacrifice
- **brilliant hunter**: on your turn illusion looks for a brilliant move (in human mode it flags real sacrifices itself, and that flag is trusted), with the move labels' brilliant rule as a backup on stockfish's best move. if there is one, a thick teal arrow with "!!" shows it and the card says "!! brilliant move: ..."
- **guess the bot**: with maia3 off, illusion's move is the first of the 3 guesses
- premoves are worked out quickly (illusion thinks 250 ms per premove, no threat search), and the threat and plan are added right after your move shows
- it uses its hand made evaluation for now (no trained net loaded yet)

## get the apk

github builds it for you. open the Actions tab and wait for the build (about 5 to 10 minutes), then download the apk from Releases ("latest build") and install it (allow "install unknown apps" for your browser).

**updating**: android only installs a new apk over the old one when both are signed with the same key. github makes a new random key for every build unless the repo has two secrets (Settings > Secrets and variables > Actions): `SIGNING_KEY_B64` (the keystore file, base64) and `SIGNING_PASSWORD`. with those set, every build installs right over the old app and keeps your settings, and the build number goes up by itself. the first build with the key still needs one last uninstall of the old app. never commit the keystore, the repo is public.

## use it

1. open the app, paste your claude api key and pick the difficulty. your side is "auto" by default: the side at the bottom of the board is yours, so switching colors between games needs nothing
2. tap "1. allow draw over other apps" and turn it on
3. tap "2. start" and allow screen capture
4. open your chess app. after a few seconds it shows 1 to 4 moves (you pick how many in the app) as arrows with a small list: dark green = best, light green = decent, yellow = acceptable, red with a skull = blunder. the numbers are stockfish's evaluation at full power, for both engines
5. stop or pause it from the app, the quick menu or the notification

## extras

- **guess the bot's move**: while the bot thinks, thin dashed arrows show its 3 most likely moves, each in its own color with a number (1 blue, 2 orange, 3 purple) (maia3 at the bot's rating if you use maia3, else stockfish's best moves). with maia3 each one shows maia3's odds, like "Nf6 52%" (update `tools/maia3_server.py` on your pc and restart it to get the odds). your answer to each guess is worked out early, so if the bot plays one of them your moves show up at once ("ready early" on the card)
- **live accuracy**: "you 87% · bot 72%" on the card, lichess style. each move is scored by how much it dropped the mover's winning chances. moves the app missed (two moves at once) are skipped
- **move labels**: every move that was just played (yours and the bot's) gets a small badge on the square it went to: brilliant, great, best, excellent, good, book, inaccuracy, mistake, miss or blunder. brilliant and great glow blue with a shimmer. it is analysis only and never changes which moves are suggested. each label costs about half a second of stockfish. the "move labels" switch turns it off, and the rating used to judge comes from the review screen
- **premove**: your answer to each guess is drawn on the board in the same color with the same number, and listed on the card ("1 Nf6 52% → e5"). so if the bot plays guess 2, play the orange premove 2. if two guesses have the same answer, both arrows are drawn side by side on that move, each in its own color with its own number. the guesses show up right away and the premoves fill in one by one (the most likely first). the "premove" switch turns it off
- **blunder alert**: on your turn, after the suggestions show up, every square where any move of yours would be a blunder turns dark red with a skull (one extra stockfish search that scores every legal move). and when a move that was just played is a blunder (yours or the bot's), its square gets a big skull that drops in and shakes. turn it off with "blunder alert"
- **hanging pieces**: on your turn, a yellow ring with a "!" on each of your pieces the opponent can take for free (or with a cheaper piece)
- **safe squares**: touch a piece in the chess app and every square it can go to gets a green dot (safe) or a red dot with an x (it gets taken there, counting what the move takes). the helper sees which piece you touched from the lit square or the app's hint dots. let go and the dots go away
- **critical moments**: the card's edge pulses and its title changes when the move really matters: "only one good move!", "big chance: the bot slipped!" or "mate in 3!"
- **bot's plan**: thin red-orange numbered arrows (1, 2, 3) show the bot's next moves if you did nothing (the same search as the threat)
- **match history**: every game the helper follows is saved by itself: result, your side, accuracy, opening, date. open it from the app ("match history"). tap a game to review it. it also counts your brilliant moves: a big card with how many you've ever made (and in how many games), a list of every brilliant move ("17. Nf5 · Italian Game · won", tap it to jump to that move in the review), a "!! 2" tag on games that have them, and a filter for only those games. the counts come from the live move labels while you play, and become exact once you open the game's review the board only shows checkmate and stalemate, so hold a game to set the result yourself after a resign or a loss on time (or to delete it)
- **pause**: hides everything and stops reading the screen until you resume

## game review

labels every move of a game like chess.com does. it is analysis only: it looks at moves that were already played and never picks or changes a move.

open it from the app ("open game review") or from the side bar ("review this game"). two ways to give it a game:

- **live game**: the game the helper is following. the helper saves every move it sees, and the review keeps up while you play (it checks for new moves every second or so)
- **paste pgn**: any pgn, or just a list of moves like `1. e4 e5 2. Nf3`. comments, variations and a FEN header are fine

labels: brilliant `!!`, great `!`, best, excellent, good, book, inaccuracy `?!`, mistake `?`, miss, blunder `??`. every position is looked at by the phone's stockfish at full power, then:

- best / excellent / good / inaccuracy / mistake / blunder = how many "expected points" the move gave away compared to the best move, with chess.com's published cut-offs (0.02, 0.05, 0.10, 0.20)
- **brilliant** = a good piece sacrifice: it leaves a piece hanging (the opponent could take it, even if taking loses) or gives up material that doesn't come back once the trades are over. you stay at least equal, it is (nearly) the best move, and the quiet moves would not have won big anyway. a sacrifice that leads to mate counts
- **great** = the only good move: every other move gives away a real chunk and drops you a step (winning to only better, equal to worse, losing to lost). never a plain recapture
- **book** = still inside known opening theory (about 8000 positions, from lichess's open chess-openings data). the opening name shows under the move
- **miss** = a small slip right after the opponent messed up, when there was something to take
- the rating picker (600 to 2500) bends the curve like chess.com does: lower ratings are judged more kindly. brilliant, great and miss have no published formula, so they are close copies of how chess.com describes them, not exact

a brilliant or great move gets a minecraft style enchant on the board: a shock ring when it lands, the enchanted item glint sliding over the piece, enchanting table runes floating up and little sparkles (teal for brilliant, blue and violet for great). there is also an accuracy score for both sides, a count of every label, an eval graph (tap or drag it to jump to a move) and a list of all the moves. "depth" picks how long stockfish thinks per position (fast / normal / deep).

the opening book is `app/src/main/assets/openings.tsv`. rebuild it with `tools/build_openings.py` (see the top of that file).

## engine control (against computer bots only)

a small round floating button: green play = you play, tap it and it turns red, now the engine plays your moves by tapping the board (from square, then to square, and the piece in the promotion picker). it plays the first move on the card, so the engine you picked decides (stockfish at your strength, maia3, or illusion). tap it again to take control back. pause also gives control back. if a move doesn't go through after a few tries, control comes back to you. drag the button anywhere.

android only lets an accessibility service tap the screen, so allow it once: main screen > "allow engine control" > chess helper engine control > on. if the switch is greyed out (android does that for apps installed from a browser), open the app's info page > ⋮ (top right) > allow restricted settings, then try again. it only taps, it reads nothing from other apps.

use it against computer bots only: chess.com and lichess ban accounts that let an engine play against people. to let illusion play people online the allowed way, use a lichess bot account.

## reading the board

- a new game is seen by itself as soon as the start position shows up, either way up. the way the board faces tells your side, so a game as black right after a game as white needs nothing (with "i play: auto")
- started in the middle of a game, your side is worked out from several clues: where the pawns stand (they only move forward), where the kings are, the board's coordinates, and which half has more white pieces
- pause clears the board, and nothing from before the pause comes back after resume
- premoves: when you premove during the bot's turn, the chess app already draws your move. the helper waits for the bot instead of believing it, then reads the bot's move and your premove together. a piece you are holding (lifted off its square) doesn't count as a move either
- a board check every few seconds compares the squares themselves with the last read, so a change too small to notice still gets read
- the bot's guesses show up faster (a shorter first search, and less waiting for the pc or illusion)
- if you (or the bot) move while the helper is still drawing, it notices and reads the board again right away, so the suggestions don't get stuck on the bot's turn
- a promoted piece is looked at a few more times after the promotion (the first look can catch the piece picker or the animation). if it was misread, it is fixed by itself, also when the piece already moved

## look

the app screens follow the ios look (grouped lists, switches, segmented controls, sliders) and switch with your phone's light / dark mode. the quick menu looks like control center. the version is shown at the bottom of the main screen.

## side bar (quick menu while playing)

touch the right (or left) edge of the screen: a small white bar shows up (or use the round button, like assistive touch). slide it towards the middle and the quick menu opens: your side, engine, strength, moves shown, eval bar / threat / best line, arrow style, effects, theme, rethink, max power (stockfish at full power for one move, a few saves per game) and new game. drag the bar up or down to move it. it hides itself when you don't touch it.

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

selftest/ReviewTest.kt checks the move labels (brilliant, great, miss, the thresholds, the opening hash, pgn text reading) with made-up engine numbers. it needs no phone, no engine and no chess library: compile it with app/src/main/java/com/example/chesshelper/{Review,Accuracy,Candidates,HaikuParse}.kt and run `ReviewTest`.
