#!/usr/bin/env python3
# MuseFloatX Windows 版（第 38 版同款流程，桌面版）
# 只做剪贴板流程，不读屏、不自动发送、不做账号池、不托管账号。
# 用法：在 X 网页里复制帖子，点桌面悬浮球，面板自动读剪贴板生成，
# 点“复制”后你自己去 X 粘贴。服务默认就是本机 muse2api：
# http://127.0.0.1:18610，模型 muse-spark，密钥填你 .env 里那串 m2a_。
# 只用 Python 标准库，不用 pip 装任何包，Windows 自带 Python 3.11+ 就能跑。
import json
import os
import queue
import threading
import tkinter as tk
from tkinter import messagebox, scrolledtext
from urllib import request as urlrequest
from urllib.error import HTTPError, URLError

APP_DIR = os.path.dirname(os.path.abspath(__file__))
SETTINGS_PATH = os.path.join(APP_DIR, "musefloatx_windows_settings.json")
DEFAULT_URL = "http://127.0.0.1:18610"
DEFAULT_MODEL = "muse-spark"
STYLES = [("自然", "自然地"), ("幽默", "幽默地"), ("简短", "简短地")]

def load_settings():
    try:
        with open(SETTINGS_PATH, "r", encoding="utf-8") as f:
            d = json.load(f)
    except Exception:
        d = {}
    return {
        "base_url": d.get("base_url", DEFAULT_URL),
        "api_key": d.get("api_key", ""),
        "model": d.get("model", DEFAULT_MODEL),
        "style": d.get("style", "自然"),
    }

def save_settings(s):
    try:
        with open(SETTINGS_PATH, "w", encoding="utf-8") as f:
            json.dump(s, f, ensure_ascii=False, indent=2)
    except Exception:
        pass

class App:
    def __init__(self, root):
        self.root = root
        self.root.title("MuseFloatX Windows 版")
        self.settings = load_settings()
        self.gen_id = 0
        self.generating = False
        self.current_post = ""
        self.current_style = self.settings["style"]
        self.msg_q = queue.Queue()
        self.root.after(100, self._drain_q)
        self._build_main()
        self._build_ball()
        self.panel = None

    # ---------- 主窗口：设置 ----------
    def _build_main(self):
        r = self.root
        r.geometry("420x300")
        tk.Label(r, text="MuseFloatX Windows 版（第 38 版同款）", font=("", 12, "bold")).pack(pady=8)
        tk.Label(r, text="服务跑在本机，不用填局域网 IP。先在 X 网页里复制帖子，再点桌面悬浮球。", wraplength=380, justify="left").pack(pady=4)
        frm = tk.Frame(r)
        frm.pack(fill="x", padx=12, pady=4)
        tk.Label(frm, text="服务地址").grid(row=0, column=0, sticky="w")
        self.url_e = tk.Entry(frm, width=42)
        self.url_e.insert(0, self.settings["base_url"])
        self.url_e.grid(row=0, column=1, pady=3)
        tk.Label(frm, text="模型").grid(row=1, column=0, sticky="w")
        self.model_e = tk.Entry(frm, width=42)
        self.model_e.insert(0, self.settings["model"])
        self.model_e.grid(row=1, column=1, pady=3)
        tk.Label(frm, text="密钥").grid(row=2, column=0, sticky="w")
        self.key_e = tk.Entry(frm, width=42, show="*")
        self.key_e.insert(0, self.settings["api_key"])
        self.key_e.grid(row=2, column=1, pady=3)
        btns = tk.Frame(r)
        btns.pack(pady=6)
        tk.Button(btns, text="保存", command=self._save).pack(side="left", padx=4)
        tk.Button(btns, text="测试连接", command=self._test).pack(side="left", padx=4)
        tk.Button(btns, text="显示悬浮球", command=self._show_ball).pack(side="left", padx=4)
        self.status = tk.Label(r, text="填好地址、模型、密钥，点保存，再点测试连接。", wraplength=380, justify="left")
        self.status.pack(pady=4)
        tk.Label(r, text="不自动发送，点复制后你自己去 X 粘贴。服务在黑窗口里跑着，关黑窗口就连不上了。", wraplength=380, justify="left", fg="gray").pack(pady=4)

    def _save(self):
        self.settings = {
            "base_url": self.url_e.get().strip().rstrip("/"),
            "api_key": self.key_e.get().strip(),
            "model": self.model_e.get().strip() or DEFAULT_MODEL,
            "style": self.current_style,
        }
        save_settings(self.settings)
        self.status.config(text="已保存。")

    def _test(self):
        self._save()
        self.status.config(text="正在测试连接…")
        threading.Thread(target=self._test_thread, daemon=True).start()

    def _test_thread(self):
        try:
            text = self._call_api("请只回复：连接成功", stream=False)
            self.msg_q.put(("status", "测试结果：" + text[:200]))
        except Exception as e:
            self.msg_q.put(("status", "测试失败：" + str(e)))

    # ---------- 悬浮球 ----------
    def _build_ball(self):
        self.ball = tk.Toplevel(self.root)
        self.ball.overrideredirect(True)
        self.ball.attributes("-topmost", True)
        self.ball.geometry("56x56+1400+300")
        self.ball.configure(bg="#7C4DFF")
        btn = tk.Button(self.ball, text="回", font=("", 20, "bold"), bg="#7C4DFF", fg="white",
                        activebackground="#7C4DFF", relief="flat", command=self._on_ball_click)
        btn.pack(fill="both", expand=True)
        self._drag = {}
        for w in (self.ball, btn):
            w.bind("<ButtonPress-1>", self._drag_start)
            w.bind("<B1-Motion>", self._drag_move)
            w.bind("<ButtonRelease-1>", self._drag_end)

    def _show_ball(self):
        self.ball.deiconify()
        self.ball.lift()

    def _drag_start(self, e):
        self._drag = {"x": e.x_root, "y": e.y_root, "moved": False, "gx": self.ball.winfo_x(), "gy": self.ball.winfo_y()}

    def _drag_move(self, e):
        if not self._drag:
            return
        dx, dy = e.x_root - self._drag["x"], e.y_root - self._drag["y"]
        if abs(dx) + abs(dy) > 4:
            self._drag["moved"] = True
        self.ball.geometry(f"+{self._drag['gx'] + dx}+{self._drag['gy'] + dy}")

    def _drag_end(self, e):
        moved = self._drag.get("moved", False)
        self._drag = {}
        if not moved:
            self._on_ball_click()

    def _on_ball_click(self):
        try:
            text = self.root.clipboard_get().strip()
        except Exception:
            text = ""
        if not text:
            messagebox.showinfo("MuseFloatX", "剪贴板是空的，先在 X 网页里复制一条帖子，再点悬浮球。")
            return
        self.current_post = text
        self._open_panel()
        self.generate(self.current_style)

    # ---------- 面板 ----------
    def _open_panel(self):
        if self.panel is not None and self.panel.winfo_exists():
            self.panel.lift()
        else:
            p = tk.Toplevel(self.root)
            p.title("MuseFloatX 回复")
            p.attributes("-topmost", True)
            p.geometry("480x560")
            self.panel = p
            tk.Label(p, text="当前帖子").pack(anchor="w", padx=10, pady=(8, 0))
            self.post_t = scrolledtext.ScrolledText(p, height=8, wrap="word")
            self.post_t.pack(fill="x", padx=10)
            sf = tk.Frame(p)
            sf.pack(pady=6)
            self.style_btns = {}
            for name, _ in STYLES:
                b = tk.Button(sf, text=name, command=lambda n=name: self.generate(n))
                b.pack(side="left", padx=4)
                self.style_btns[name] = b
            tk.Label(p, text="AI 回复").pack(anchor="w", padx=10)
            self.reply_t = scrolledtext.ScrolledText(p, height=10, wrap="word")
            self.reply_t.pack(fill="both", expand=True, padx=10)
            bf = tk.Frame(p)
            bf.pack(pady=8)
            tk.Button(bf, text="复制", command=self._copy_reply).pack(side="left", padx=4)
            tk.Button(bf, text="重新生成", command=lambda: self.generate(self.current_style)).pack(side="left", padx=4)
            tk.Button(bf, text="关闭", command=p.destroy).pack(side="left", padx=4)
        self.post_t.delete("1.0", "end")
        self.post_t.insert("1.0", self.current_post[:300] + ("…" if len(self.current_post) > 300 else ""))
        self.reply_t.delete("1.0", "end")
        self._refresh_style_btns()

    def _refresh_style_btns(self):
        if not hasattr(self, "style_btns"):
            return
        for name, b in self.style_btns.items():
            b.config(relief="sunken" if name == self.current_style else "raised")

    def _copy_reply(self):
        text = self.reply_t.get("1.0", "end").strip()
        if not text:
            return
        self.root.clipboard_clear()
        self.root.clipboard_append(text)
        messagebox.showinfo("MuseFloatX", "已复制，去 X 粘贴，不自动发送。")

    # ---------- 生成：单飞行 ----------
    def generate(self, style):
        if self.generating and style == self.current_style:
            return  # 生成中点已选风格，直接忽略
        # 生成中点另一风格：先取消当前一通，再用新风格、同一原文提交一次
        self.gen_id += 1
        my_id = self.gen_id
        self.current_style = style
        self.settings["style"] = style
        save_settings(self.settings)
        self._refresh_style_btns()
        if self.panel is not None:
            self.reply_t.delete("1.0", "end")
            self.reply_t.insert("1.0", "正在生成…")
        self.generating = True
        threading.Thread(target=self._gen_thread, args=(my_id, style, self.current_post), daemon=True).start()

    def _gen_thread(self, my_id, style, post):
        try:
            text = self._call_api_stream(post, style, my_id)
            if my_id == self.gen_id:
                self.msg_q.put(("reply", text))
        except Exception as e:
            if my_id == self.gen_id:
                self.msg_q.put(("reply", "生成失败：" + str(e)))
        finally:
            if my_id == self.gen_id:
                self.generating = False

    def _style_word(self, style):
        return dict(STYLES).get(style, "自然地")

    def _call_api(self, user_text, stream=False):
        # 非流式测试用：直接返回全文
        url = self.settings["base_url"].rstrip("/") + "/v1/chat/completions"
        body = json.dumps({
            "model": self.settings["model"], "stream": False, "temperature": 0.7,
            "messages": [{"role": "user", "content": user_text}],
        }).encode("utf-8")
        req = urlrequest.Request(url, data=body, headers={
            "Authorization": "Bearer " + self.settings["api_key"],
            "Content-Type": "application/json",
        }, method="POST")
        try:
            with urlrequest.urlopen(req, timeout=90) as resp:
                data = json.loads(resp.read().decode("utf-8"))
        except HTTPError as e:
            raise Exception(f"HTTP {e.code}：{e.read().decode('utf-8', 'ignore')[:500]}")
        except URLError as e:
            raise Exception("连不上服务，请确认黑窗口开着、地址填对了：" + str(e.reason))
        content = data["choices"][0]["message"]["content"]
        return content.strip() if isinstance(content, str) else ""

    def _call_api_stream(self, post, style, my_id):
        url = self.settings["base_url"].rstrip("/") + "/v1/chat/completions"
        system = f"你是一个回复助手。根据这个内容，{self._style_word(style)}回复。"
        user = f"X 帖子：\n{post[:2000]}\n回一条，别自动发送。"
        body = json.dumps({
            "model": self.settings["model"], "stream": True, "temperature": 0.7,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        }).encode("utf-8")
        req = urlrequest.Request(url, data=body, headers={
            "Authorization": "Bearer " + self.settings["api_key"],
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
        }, method="POST")
        try:
            resp = urlrequest.urlopen(req, timeout=90)
        except HTTPError as e:
            raise Exception(f"HTTP {e.code}：{e.read().decode('utf-8', 'ignore')[:500]}")
        except URLError as e:
            raise Exception("连不上服务，请确认黑窗口开着、地址填对了：" + str(e.reason))
        out = []
        try:
            for raw in resp:
                if my_id != self.gen_id:
                    break  # 已被新风格替换，断开这通，服务端释锁
                line = raw.decode("utf-8", "ignore").strip()
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    break
                try:
                    piece = json.loads(data)["choices"][0]["delta"].get("content", "")
                except Exception:
                    continue
                if piece:
                    out.append(piece)
                    self.msg_q.put(("delta", piece))
        finally:
            try:
                resp.close()
            except Exception:
                pass
        text = "".join(out).strip()
        if not text:
            raise Exception("服务返回了空回复")
        return text

    def _drain_q(self):
        try:
            while True:
                kind, val = self.msg_q.get_nowait()
                if kind == "status":
                    self.status.config(text=val)
                elif kind == "delta":
                    if self.panel is not None and self.panel.winfo_exists():
                        cur = self.reply_t.get("1.0", "end")
                        if cur.strip() == "正在生成…":
                            self.reply_t.delete("1.0", "end")
                        self.reply_t.insert("end", val)
                        self.reply_t.see("end")
                elif kind == "reply":
                    if self.panel is not None and self.panel.winfo_exists():
                        self.reply_t.delete("1.0", "end")
                        self.reply_t.insert("1.0", val)
        except queue.Empty:
            pass
        self.root.after(100, self._drain_q)

if __name__ == "__main__":
    root = tk.Tk()
    App(root)
    root.mainloop()
