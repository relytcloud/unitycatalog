#!/usr/bin/env bash
# uc_add_jwks_key.sh <public_key.jwk.json> <jwks_file>
#
# UC 运维侧使用:把 Relyt 交付的 JWKS 条目合并进 server.external-jwks-file 指向的文件。
#
# 参数 1  public_key.jwk.json —— Relyt 交付的【单条】公钥条目(gen_uc_instance_key.sh 的产物),
#         JSON 对象,格式与字段约束:
#           {
#            "kty": "EC",            # 固定 EC
#            "crv": "P-256",         # 固定 P-256
#            "kid": "<64位hex>",     # 密钥编号 = 公钥 DER 的 SHA-256 指纹(本脚本会复核)
#            "use": "sig",           # 固定 sig
#            "alg": "ES256",         # 固定 ES256
#            "x":   "<base64url>",   # EC 公钥点 X 坐标,解码后必须 32 字节
#            "y":   "<base64url>"    # EC 公钥点 Y 坐标,解码后必须 32 字节
#           }
#         注意是单个对象,不是 {"keys":[...]} 的完整 JWKS——传错会明确报错。
#
# 参数 2  jwks_file —— UC 侧的公钥登记总文件,即 server.properties 中
#         server.external-jwks-file= 所指向的路径(所有 Relyt 实例的公钥都登记在这一个文件里)。
#
# 行为:
# - 所有字段先校验,任一不符合预期立刻报错退出,不动登记文件。
# - 同 kid 已存在则原样替换,否则追加(轮转期间新旧 kid 并存,各验各的)。
# - 写临时文件后原子 mv,换发请求随时进来也不会读到半截文件。
# - 仅密钥轮转/新增 kid:改完即生效,无需重启 UC。
#   新增 Relyt 实例(新 issuer):还需在 server.properties 的 server.allowed-issuers
#   追加该实例 id,并重启 UC。
set -euo pipefail
ENTRY="${1:?usage: $0 <public_key.jwk.json> <jwks_file> <issuer>}"
JWKS="${2:?usage: $0 <public_key.jwk.json> <jwks_file> <issuer>}"
# issuer = the Relyt instance id this key belongs to (gen_uc_instance_key.sh -> issuer.txt).
# UC binds the key to this issuer and only accepts it for tokens whose iss == this value, so a
# key registered for one instance can never sign a token accepted as another issuer.
ISSUER="${3:?usage: $0 <public_key.jwk.json> <jwks_file> <issuer>  (issuer/instance id; must be in server.allowed-issuers)}"

python3 - "$ENTRY" "$JWKS" "$ISSUER" <<'PYEOF'
import base64, hashlib, json, os, re, sys, tempfile

entry_file, jwks_file, issuer = sys.argv[1], sys.argv[2], sys.argv[3]
if not issuer.strip():
    print("ERROR: issuer (arg 3) must be non-empty", file=sys.stderr)
    sys.exit(1)

def die(msg):
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)

try:
    entry = json.load(open(entry_file))
except Exception as e:
    die(f"'{entry_file}' is not valid JSON: {e}")

if not isinstance(entry, dict):
    die(f"'{entry_file}' must be a single JSON object")
if "keys" in entry:
    die(f"'{entry_file}' looks like a full JWKS ({{\"keys\":[...]}}); "
        "pass the single key entry (public_key.jwk.json) instead")

# -- 固定值字段 --
for field, expected in (("kty", "EC"), ("crv", "P-256"), ("alg", "ES256"), ("use", "sig")):
    got = entry.get(field)
    if got != expected:
        die(f"field '{field}' must be '{expected}', got {got!r}")

# -- kid:64 位 hex --
kid = entry.get("kid", "")
if not re.fullmatch(r"[0-9a-f]{64}", kid):
    die(f"field 'kid' must be 64 lowercase hex chars, got {kid!r}")

# -- x/y:base64url,解码后 32 字节 --
def b64u_decode(name):
    v = entry.get(name)
    if not isinstance(v, str) or not v:
        die(f"field '{name}' is missing or not a string")
    try:
        raw = base64.urlsafe_b64decode(v + "=" * (-len(v) % 4))
    except Exception:
        die(f"field '{name}' is not valid base64url")
    if len(raw) != 32:
        die(f"field '{name}' must decode to 32 bytes, got {len(raw)}")
    return raw

x, y = b64u_decode("x"), b64u_decode("y")

# -- kid 必须等于公钥 DER 的 SHA-256 指纹(与生成脚本约定一致,防止 kid 与密钥配错对)--
# P-256 SubjectPublicKeyInfo DER = 固定前缀 + 0x04 + X(32) + Y(32)
SPKI_PREFIX = bytes.fromhex(
    "3059301306072a8648ce3d020106082a8648ce3d030107034200")
fingerprint = hashlib.sha256(SPKI_PREFIX + b"\x04" + x + y).hexdigest()
if fingerprint != kid:
    die(f"kid does not match the key fingerprint: kid={kid[:16]}..., "
        f"sha256(pubkey DER)={fingerprint[:16]}... — entry corrupted or mispaired")

# -- 绑定 issuer:UC 仅在 token 的 iss 等于该值时才接受此 key(防 confused-issuer)--
entry["issuer"] = issuer

# -- 合并(同 kid 替换,新 kid 追加),原子写回 --
jwks = {"keys": []}
if os.path.exists(jwks_file):
    try:
        jwks = json.load(open(jwks_file))
    except Exception as e:
        die(f"existing jwks file '{jwks_file}' is not valid JSON: {e}")
    if not isinstance(jwks.get("keys"), list):
        die(f"existing jwks file '{jwks_file}' has no 'keys' array")

replaced = any(k.get("kid") == kid for k in jwks["keys"])
jwks["keys"] = [k for k in jwks["keys"] if k.get("kid") != kid]
jwks["keys"].append(entry)

fd, tmp = tempfile.mkstemp(dir=os.path.dirname(os.path.abspath(jwks_file)))
with os.fdopen(fd, "w") as f:
    json.dump(jwks, f, indent=1)
os.replace(tmp, jwks_file)
action = "replaced" if replaced else "added"
print(f"{action} kid={kid[:16]}... total keys={len(jwks['keys'])}")
PYEOF
