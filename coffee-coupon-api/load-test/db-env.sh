# run-all.sh, verify.sh가 source해서 쓰는 DB 접속 기본값.
# 저장소 루트의 .env(docker-compose와 같은 파일)를 기준으로 하되, 이미 지정한 환경 변수가 있으면 그 값을 우선한다.
# 다른 MySQL을 쓰려면 MYSQL_TCP_PORT, MYSQL_PWD를 직접 지정한다.

ENV_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/.env"
if [ -f "$ENV_FILE" ]; then
  while IFS='=' read -r key value; do
    case "$key" in ''|\#*) continue ;; esac
    if [ -z "${!key:-}" ]; then export "$key=$value"; fi
  done < "$ENV_FILE"
fi

# mysql 명령은 MYSQL_TCP_PORT, MYSQL_PWD 환경 변수를 읽는다. 지정하지 않으면 3306에 붙으므로 .env 값을 넘긴다.
export MYSQL_TCP_PORT="${MYSQL_TCP_PORT:-${MYSQL_PORT:-}}"
export MYSQL_PWD="${MYSQL_PWD:-${MYSQL_PASSWORD:-}}"
export DB_HOST="${DB_HOST:-127.0.0.1}"
export DB_USER="${DB_USER:-root}"
export DB_NAME="${DB_NAME:-${MYSQL_DATABASE:-}}"
