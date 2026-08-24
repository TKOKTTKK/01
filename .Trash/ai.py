

python3 ai.py
你好
cat << 'EOF' > ai.py
import urllib.request
import json
import os

url = "https://lyozc.com/v1/chat/completions"
api_key = "sk-feb82e3b987f6a709f23798b38bd442b0ebf2050d1a72e87cf6d0c61d6ab5ed1"
model = "claude-sonnet-5"

history = []

print("=== AI 控制台已就绪 (输入 exit 退出) ===")

while True:
    try:
        user_input = input("\n你: ").strip()
        if not user_input:
            continue
        if user_input.lower() in ["exit", "quit", "退出"]:
            break

        history.append({"role": "user", "content": user_input})

        req = urllib.request.Request(
            url,
            data=json.dumps({"model": model, "messages": history}).encode("utf-8"),
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"}
        )

        print("AI 思考中...", end="\r", flush=True)

        with urllib.request.urlopen(req) as resp:
            res = json.loads(resp.read().decode("utf-8"))
            reply = res["choices"][0]["message"]["content"]
            history.append({"role": "assistant", "content": reply})
            print(f"AI: {reply}")

    except KeyboardInterrupt:
        break
    except Exception as e:
        print(f"\n[错误]: {e}")
EOF


echo 'import urllib.request, json' > ai.py
echo 'url = "https://lyozc.com/v1/chat/completions"' >> ai.py
echo 'key = "sk-feb82e3b987f6a709f23798b38bd442b0ebf2050d1a72e87cf6d0c61d6ab5ed1"' >> ai.py
echo 'history = []' >> ai.py
echo 'print("=== AI 对话已就绪 ===")' >> ai.py
echo 'while True:' >> ai.py
echo '    q = input("\n你: ").strip()' >> ai.py
echo '    if not q: continue' >> ai.py
echo '    history.append({"role": "user", "content": q})' >> ai.py
echo '    req = urllib.request.Request(url, data=json.dumps({"model": "claude-sonnet-5", "messages": history}).encode(), headers={"Content-Type": "application/json", "Authorization": "Bearer " + key})' >> ai.py
echo '    try:' >> ai.py
echo '        with urllib.request.urlopen(req) as r:' >> ai.py
echo '            res = json.loads(r.read().decode())' >> ai.py
echo '            reply = res["choices"][0]["message"]["content"]' >> ai.py
echo '            history.append({"role": "assistant", "content": reply})' >> ai.py
echo '            print("AI: " + reply)' >> ai.py
echo '    except Exception as e: print("错误: " + str(e))' >> ai.py






python3 -c 'code = "import urllib.request, json\nurl = \"https://lyozc.com/v1/chat/completions\"\nkey = \"sk-feb82e3b987f6a709f23798b38bd442b0ebf2050d1a72e87cf6d0c61d6ab5ed1\"\nhistory = []\nprint(\"=== AI 对话已就绪 ===\")\nwhile True:\n    try:\n        q = input(\"\\n你: \").strip()\n        if not q: continue\n        if q in [\"exit\", \"quit\"]: break\n        history.append({\"role\": \"user\", \"content\": q})\n        req = urllib.request.Request(url, data=json.dumps({\"model\": \"claude-sonnet-5\", \"messages\": history}).encode(), headers={\"Content-Type\": \"application/json\", \"Authorization\": \"Bearer \" + key})\n        with urllib.request.urlopen(req) as r:\n            res = json.loads(r.read().decode())\n            reply = res[\"choices\"][0][\"message\"][\"content\"]\n            history.append({\"role\": \"assistant\", \"content\": reply})\n            print(\"\\nAI: \" + reply)\n    except Exception as e:\n        print(\"\\n[错误]: \" + str(e))\n"; open("ai.py", "w").write(code)'




python3 -c 'import urllib.request, json; u, k, h = "https://lyozc.com/v1/chat/completions", "sk-feb82e3b987f6a709f23798b38bd442b0ebf2050d1a72e87cf6d0c61d6ab5ed1", []; print("=== AI 已就绪 (输入 exit 退出) ==="); [(h.append({"role":"user","content":q}), print("AI 思考中..."), (lambda r: (h.append({"role":"assistant","content":r}), print(f"\nAI: {r}\n")))(json.loads(urllib.request.urlopen(urllib.request.Request(u, data=json.dumps({"model":"claude-sonnet-5","messages":h}).encode(), headers={"Content-Type":"application/json","Authorization":f"Bearer {k}"})).read().decode())["choices"][0]["message"]["content"])) for q in iter(lambda: input("你: ").strip(), "exit") if q]'





pyt