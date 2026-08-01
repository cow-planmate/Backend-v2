#!/usr/bin/env bash
# JWT 서명용 RSA 키페어 생성.
#
#   bash gen-jwt-key.sh
#
# 개인키를 표준출력이 아니라 파일로 떨군다 — 터미널 스크롤백과 셸 히스토리에 남기지 않기 위함이다.
# 공개키는 따로 배포할 필요가 없다. 서비스가 개인키에서 유도해 /.well-known/jwks.json 으로
# 게시하고, 커뮤니티는 거기서 받아간다.

set -euo pipefail

OUT="${1:-jwt-private-key.pem}"

if [[ -e "$OUT" ]]; then
    echo "이미 있다: $OUT — 덮어쓰지 않는다. 교체하려면 먼저 옮기거나 지울 것." >&2
    exit 1
fi

umask 077
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$OUT" >/dev/null 2>&1
chmod 600 "$OUT"

echo "생성: $OUT (600)"
echo
echo "== .env 에 넣는 법 =="
echo "PEM 은 여러 줄이라 .env 한 줄에 그대로 못 넣는다. \\n 으로 접어 넣는다:"
echo
echo "  JWT_PRIVATE_KEY=\"\$(awk 'BEGIN{ORS=\"\\\\n\"}1' $OUT)\""
echo
echo "docker compose 는 .env 의 \\n 을 실제 줄바꿈으로 풀지 않으므로, 서비스 쪽에서"
echo "공백을 전부 제거해 읽는다(JwtSigningKeys#readPrivateKey) — 접힌 채로 넣어도 동작한다."
echo
echo "== 주의 =="
echo "이 파일이 유출되면 누구나 이 서비스의 토큰을 위조할 수 있다. 저장소에 커밋하지 말 것"
echo "(.gitignore 에 *.pem 이 있는지 확인). 교체하려면 새 키로 재기동하면 되고, kid 가 바뀌므로"
echo "커뮤니티는 JWKS 를 다시 받아 자동으로 따라온다 — 다만 기존 토큰은 그 즉시 무효가 된다."
