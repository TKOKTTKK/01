import numpy as np

# ======================
# 1. 数据（字符级）
# ======================
text = "hello ai hello transformer hello world ai ai hello"

chars = list(set(text))
vocab = len(chars)

char2id = {c:i for i,c in enumerate(chars)}
id2char = {i:c for c,i in char2id.items()}

data = [char2id[c] for c in text]

# ======================
# 2. 超参数
# ======================
seq_len = 5
d_model = 16
lr = 0.01

np.random.seed(1)

# ======================
# 3. 参数
# ======================
E = np.random.randn(vocab, d_model) * 0.1

Wq = np.random.randn(d_model, d_model) * 0.1
Wk = np.random.randn(d_model, d_model) * 0.1
Wv = np.random.randn(d_model, d_model) * 0.1

Wo = np.random.randn(d_model, vocab) * 0.1

# ======================
# 4. softmax
# ======================
def softmax(x):
    x = x - np.max(x)
    e = np.exp(x)
    return e / np.sum(e)

# ======================
# 5. forward（正确Transformer）
# ======================
def forward(x):

    X = E[x]  # (seq, d_model)

    Q = X @ Wq
    K = X @ Wk
    V = X @ Wv

    scores = Q @ K.T / np.sqrt(d_model)

    # causal mask（不能看未来）
    mask = np.triu(np.ones_like(scores) * -1e9, 1)
    scores += mask

    A = np.array([softmax(row) for row in scores])  # (seq, seq)

    Z = A @ V  # (seq, d_model)

    logits = Z @ Wo  # (seq, vocab)

    return logits, (X, Q, K, V, A, Z)

# ======================
# 6. 训练
# ======================
loss_history = []

for epoch in range(200):

    loss = 0

    for i in range(len(data) - seq_len - 1):

        x = data[i:i+seq_len]
        y = data[i+seq_len]

        logits, cache = forward(x)

        pred = softmax(logits[-1])

        loss += -np.log(pred[y] + 1e-8)

        # ======================
        # 7. 反向传播（稳定版关键修复）
        # ======================

        X, Q, K, V, A, Z = cache

        # dlogits
        dlogits = np.zeros_like(pred)
        dlogits[y] = 1
        dlogits -= pred  # softmax gradient

        # output layer
        dWo = np.outer(Z[-1], dlogits)
        Wo -= lr * dWo

        # gradient to Z
        dZ = np.zeros_like(Z)
        dZ[-1] = Wo @ dlogits  # 只影响最后token（稳定关键）

        # attention backward（稳定简化版）
        dV = A.T @ dZ

        dA = dZ @ V.T

        dS = dA * A * (1 - A)

        dQ = dS @ K
        dK = dS.T @ Q

        Wq -= lr * (X.T @ dQ)
        Wk -= lr * (X.T @ dK)
        Wv -= lr * (X.T @ dV)

        # embedding update
        dX = (dQ @ Wq.T) + (dK @ Wk.T) + (dV @ Wv.T)

        for t in range(len(x)):
            E[x[t]] -= lr * dX[t]

    loss_history.append(loss)

    if epoch % 20 == 0:
        print(f"epoch {epoch}, loss {loss:.4f}")

# ======================
# 7. 生成
# ======================
def generate(start, length=50):

    x = [char2id[c] for c in start]

    out = start

    for _ in range(length):

        inp = x[-seq_len:] if len(x) >= seq_len else x

        logits, _ = forward(inp)

        prob = softmax(logits[-1])

        idx = np.random.choice(range(vocab), p=prob)

        x.append(idx)
        out += id2char[idx]

    return out

print("\n生成结果：")
print(generate("hello", 80))