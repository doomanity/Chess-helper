#!/usr/bin/env python3
"""
maia3 server for the chess helper app. run it on your pc (phone and pc on the same wifi).

setup on the pc (once):
  pip install torch
  pip install git+https://github.com/CSSLab/maia3.git
  maia3-cache --model 23m

run:
  python maia3_server.py                 (default model 23m, cpu)
  python maia3_server.py --device cuda   (if you have an nvidia gpu)

it prints the address to type into the app (like 192.168.1.20:8765).
the app sends the position and the rating you picked, this script answers with maia3's move.
it also says how likely each move is (maia3's own odds), so the app can show "Nf6 52%" for the bot's guesses.
for that it runs maia3 inside this script. if that doesn't work it falls back to the maia3-uci program (no odds then).
"""
import argparse
import json
import shlex
import socket
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Uci:
    """one running uci engine process"""

    def __init__(self, cmd):
        self.cmd = cmd
        self.proc = subprocess.Popen(
            cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1
        )
        self.send("uci")
        self.wait("uciok")
        self.send("isready")
        self.wait("readyok")

    def send(self, line):
        self.proc.stdin.write(line + "\n")
        self.proc.stdin.flush()

    def wait(self, prefix):
        while True:
            line = self.proc.stdout.readline()
            if not line:
                raise RuntimeError("engine stopped")
            if line.startswith(prefix):
                return line.strip()

    def option(self, name, value):
        self.send(f"setoption name {name} value {value}")

    def best(self, fen, n=1):
        """the move maia3 plays, plus (when n > 1) the n most likely human moves, most likely first"""
        self.option("MultiPV", n)
        self.send("position fen " + fen)
        self.send("go nodes 1")  # maia3 answers with one forward pass
        found = {}
        while True:
            line = self.proc.stdout.readline()
            if not line:
                raise RuntimeError("engine stopped")
            if line.startswith("info") and " multipv " in line and " pv " in line:
                t = line.split()
                try:
                    found[int(t[t.index("multipv") + 1])] = t[t.index("pv") + 1]
                except (ValueError, IndexError):
                    pass
            if line.startswith("bestmove"):
                best = line.split()[1]
                break
        moves = [found[k] for k in sorted(found)]
        if best in moves:
            moves.remove(best)
        return best, ([best] + moves)[:n]

    def sync(self):
        self.send("isready")
        self.wait("readyok")

    def close(self):
        try:
            self.send("quit")
            self.proc.terminate()
        except Exception:
            pass


class Direct:
    """maia3 run inside this script instead of as a uci program: the same model, but we also get the odds of
    every move (maia3's uci output doesn't print them)"""

    def __init__(self, m3, model, elo, device):
        argv = ["--model", model, "--elo", str(elo), "--device", device, "--use-uci-history"]
        if device == "cpu":
            argv.append("--no-use-amp")
        self.eng = m3.Maia3UCIEngine(m3.parse_args(argv))
        self.eng.ensure_model_loaded()

    def option(self, name, value):
        self.eng.cmd_setoption(f"setoption name {name} value {value}")

    def sync(self):
        pass

    def best(self, fen, n=1):
        """like Uci.best, plus the odds (0..1) of each listed move"""
        self.eng.multipv = max(1, min(20, max(n, 3)))
        self.eng.cmd_position("position fen " + fen)
        move, top = self.eng.score_moves()
        if move is None:
            raise RuntimeError("no legal move in this position")
        odds = {t["move"].uci(): float(t["policy"]) for t in top}
        ranked = [t["move"].uci() for t in top]
        best = move.uci()
        if best in ranked:
            ranked.remove(best)
        moves = ([best] + ranked)[:n]
        return best, moves, [round(odds.get(m, 0.0), 4) for m in moves]

    def close(self):
        pass


class Pool:
    """keeps one engine per model. rating, opponent rating and temperature are changed per request (uci options),
    so asking for the bot's likely move and then for yours doesn't reload the model"""

    def __init__(self, args):
        self.args = args
        self.lock = threading.Lock()
        self.key = None
        self.engine = None
        self.opts = {}

    def command(self, model, elo):
        if self.args.cmd:
            return shlex.split(self.args.cmd.format(model=model, elo=elo))
        cmd = ["maia3-uci", "--model", model, "--elo", str(elo), "--device", self.args.device, "--use-uci-history"]
        if self.args.device == "cpu":
            cmd.append("--no-use-amp")
        return cmd

    def set(self, name, value):
        value = str(value)
        if self.opts.get(name) != value:
            self.engine.option(name, value)
            self.opts[name] = value
            return True
        return False

    def move(self, fen, model, elo, n=1, oppo=None, temperature=None):
        with self.lock:
            if model != self.key:
                if self.engine:
                    self.engine.close()
                print(f"starting engine: model {model} (the first start loads the model, be patient)")
                self.engine = None
                if not self.args.cmd and not self.args.uci:
                    try:
                        from maia3 import uci as m3
                        self.engine = Direct(m3, model, elo, self.args.device)
                        print("maia3 runs inside the server: move odds on")
                    except Exception as e:
                        print(f"couldn't run maia3 inside the server ({e}), using the maia3-uci program (no move odds)")
                if self.engine is None:
                    self.engine = Uci(self.command(model, elo))
                self.opts = {}
                self.key = model
            # temperature 0 = always maia's most likely move (strongest). around 1 = it picks like a real human,
            # with a human's randomness (now and then a mistake)
            temp = self.args.temperature if temperature is None else temperature
            changed = self.set("Temperature", temp)
            changed |= self.set("SelfElo", elo)
            changed |= self.set("OppoElo", oppo or self.args.oppo_elo or elo)
            if changed:
                self.engine.sync()
            try:
                found = self.engine.best(fen, n)
                return found if len(found) == 3 else (found[0], found[1], None)
            except Exception:
                self.key = None  # the engine died (bad position?), start a fresh one next time
                raise


def local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "your-pc-ip"


def make_handler(pool, default_model, default_elo):
    class Handler(BaseHTTPRequestHandler):
        def reply(self, code, obj):
            body = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("content-type", "application/json")
            self.send_header("content-length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            self.reply(200, {"ok": True})

        def do_POST(self):
            try:
                n = int(self.headers.get("content-length", "0"))
                req = json.loads(self.rfile.read(n) or b"{}")
                fen = req["fen"]
                model = str(req.get("model") or default_model)
                elo = int(req.get("elo") or default_elo)
                n = max(1, min(8, int(req.get("n") or 1)))
                oppo = int(req.get("oppo_elo") or 0) or None
                temp = req.get("temperature")
                temp = None if temp is None else round(max(0.0, min(2.0, float(temp))), 2)
                mv, moves, odds = pool.move(fen, model, elo, n, oppo, temp)
                who = req.get("for") or "you"
                shown = moves if odds is None else [f"{m} {round(p * 100)}%" for m, p in zip(moves, odds)]
                print(f"[{who} {elo}] {fen}  ->  {'  '.join(shown)}")
                out = {"move": mv, "moves": moves}
                if odds is not None:
                    out["probs"] = odds
                self.reply(200, out)
            except Exception as e:
                print("error:", e)
                self.reply(500, {"error": str(e)})

        def log_message(self, *a):
            pass

    return Handler


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--model", default="23m", help="5m, 23m or 79m")
    ap.add_argument("--elo", type=int, default=1500)
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--temperature", default="0", help="0 = always the most likely move (strongest). 1 = human-like randomness")
    ap.add_argument("--oppo-elo", type=int, default=0, help="rating of the opponent bot you play, 0 = same as your maia rating")
    ap.add_argument("--cmd", default=None, help="advanced: run another uci engine instead, {model} and {elo} get filled in")
    ap.add_argument("--uci", action="store_true", help="use the maia3-uci program instead of running maia3 inside (no move odds)")
    args = ap.parse_args()
    pool = Pool(args)
    srv = ThreadingHTTPServer(("0.0.0.0", args.port), make_handler(pool, args.model, args.elo))
    print(f"maia3 server ready. in the app type this address:  {local_ip()}:{args.port}")
    print(f"away from home with tailscale? use your pc's 100.x.x.x tailscale address instead, with :{args.port}")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nbye")


if __name__ == "__main__":
    main()
