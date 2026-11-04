"""
builds app/src/main/assets/openings.tsv, the opening book used by the game review ("book" moves).

data: https://github.com/lichess-org/chess-openings (CC0). put a.tsv ... e.tsv in a folder and run:

    pip install chess
    python tools/build_openings.py <folder with the tsv files> app/src/main/assets/openings.tsv

every line of the output is   <hash as 8 hex digits> TAB <opening name>
the hash is fnv-1a (32 bit) of "<pieces> <side to move> <castling>" and must match OpeningBook.key in Review.kt
"""
import csv
import io
import sys

import chess
import chess.pgn


def key(board: chess.Board) -> str:
    return "%s %s %s" % (board.board_fen(), "w" if board.turn else "b", board.castling_xfen())


def fnv(s: str) -> int:
    h = 0x811C9DC5
    for b in s.encode("utf-8"):
        h ^= b
        h = (h * 16777619) & 0xFFFFFFFF
    return h


def main(folder: str, out: str) -> None:
    lines = []
    for f in "abcde":
        with open("%s/%s.tsv" % (folder, f), newline="", encoding="utf-8") as fh:
            for row in csv.DictReader(fh, delimiter="\t"):
                lines.append((row["name"], row["pgn"]))
    # the exact end of a line gets that line's name; the positions on the way to it take the name of the line
    # that goes through them, unless some other line ends right there
    ends = {}
    through = {}
    for name, pgn in lines:
        game = chess.pgn.read_game(io.StringIO(pgn))
        b = game.board()
        keys = []
        for mv in game.mainline_moves():
            b.push(mv)
            keys.append(fnv(key(b)))
        if not keys:
            continue
        ends.setdefault(keys[-1], name)
        for k in keys[:-1]:
            through.setdefault(k, name)
    book = dict(through)
    book.update(ends)
    with open(out, "w", encoding="utf-8", newline="\n") as fh:
        for k in sorted(book):
            fh.write("%08x\t%s\n" % (k, book[k]))
    print("wrote %d positions from %d lines" % (len(book), len(lines)))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1], sys.argv[2])
