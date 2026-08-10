# EC2 CI/CD 초기 설정

이 문서는 `.github/workflows/deploy.yml`이 SCP로 전달한 릴리스를 systemd로 실행하기
위한 EC2 최초 1회 설정 절차입니다. 예시는 Ubuntu와 배포 사용자 `ubuntu`를 기준으로
합니다.

## 1. 배포 대상 확인

GitHub Actions의 `EC2_HOSTS` Repository Variable은 다음 형식을 권장합니다.

```json
{"frontend":["FRONTEND_PUBLIC_IP_OR_DNS"],"backend":["BACKEND_PUBLIC_IP_OR_DNS"]}
```

백엔드 API와 Socket.IO 대상 그룹이 서로 다른 EC2를 사용한다면 두 주소를 모두
`backend` 배열에 넣습니다. GitHub 호스팅 러너는 VPC의 사설 IP에 직접 접속할 수
없으므로 Public IP/DNS 또는 별도의 self-hosted runner/SSM 연결이 필요합니다.

## 2. GitHub Actions로 초기화

`Bootstrap EC2` 워크플로우는 Ubuntu에서 필요한 런타임(Node.js 22 또는 Java 25),
systemd 유닛, 배포 디렉터리 및 제한된 sudo 재시작 권한을 자동으로 구성합니다.
백엔드 초기화에는 아래의 multiline GitHub Secret이 추가로 필요합니다.

```text
BACKEND_ENV
```

`BACKEND_ENV` 값은 다음 환경 파일의 전체 내용입니다.

```env
ENCRYPTION_KEY=64자리_HEX_키
ENCRYPTION_SALT=솔트
JWT_SECRET=충분히_긴_랜덤_시크릿
MONGO_URI=mongodb://MONGODB_PRIVATE_HOST:27017/bootcamp-chat
REDIS_HOST=REDIS_PRIVATE_HOST
REDIS_PORT=6379
REDIS_PASSWORD=실제_비밀번호
PORT=5001
WS_PORT=5002
OPENAI_API_KEY=실제_OpenAI_API_키
CORS_ALLOWED_ORIGINS=https://chat.goorm-ktb-007.goorm.team
SOCKETIO_SERVER_ORIGIN=https://chat.goorm-ktb-007.goorm.team
```

GitHub 저장소에서 **Actions → Bootstrap EC2 → Run workflow**를 열고 `all`을
선택하면 `EC2_HOSTS`의 frontend/backend 인벤토리별로 필요한 초기화가 실행됩니다.
역할별 초기화만 필요할 때는 `frontend` 또는 `backend`를 선택합니다.

기존 서비스 노드에 영향을 주지 않고 새 EC2만 초기화하려면 Run workflow의 `hosts`
입력에 신규 노드만 담은 JSON을 입력합니다. 예:

```json
{"frontend":["NEW_FRONTEND_HOST"],"backend":["NEW_BACKEND_HOST_1","NEW_BACKEND_HOST_2"]}
```

초기화와 헬스체크가 끝난 뒤 일반 배포용 `EC2_HOSTS`에는 기존·신규 전체 노드를
등록합니다. 그래야 이후 배포가 모든 운영 인스턴스에 동일하게 적용됩니다.

Maven, pnpm 및 전체 소스는 EC2에 설치하지 않습니다. 빌드는 GitHub Actions에서
수행하고 EC2는 런타임과 산출물만 사용합니다.

## 3. 수동 프로비저닝 대안

로컬 저장소 루트에서 실행합니다.

```bash
scp -i /path/to/key.pem scripts/provision-ec2-systemd.sh \
  ubuntu@FRONTEND_HOST:/tmp/
scp -i /path/to/key.pem scripts/provision-ec2-systemd.sh \
  ubuntu@BACKEND_HOST:/tmp/
```

프론트엔드 EC2:

```bash
ssh -i /path/to/key.pem ubuntu@FRONTEND_HOST
chmod +x /tmp/provision-ec2-systemd.sh
sudo NODE_BIN="$(command -v node)" \
  /tmp/provision-ec2-systemd.sh frontend ubuntu
```

백엔드 EC2:

```bash
ssh -i /path/to/key.pem ubuntu@BACKEND_HOST
chmod +x /tmp/provision-ec2-systemd.sh
sudo JAVA_BIN="$(command -v java)" \
  /tmp/provision-ec2-systemd.sh backend ubuntu
```

같은 EC2에서 두 애플리케이션을 실행하면 `all` 역할을 사용합니다.

```bash
sudo NODE_BIN="$(command -v node)" JAVA_BIN="$(command -v java)" \
  /tmp/provision-ec2-systemd.sh all ubuntu
```

스크립트는 배포 디렉터리, systemd 유닛, 배포용으로 제한된 sudo 재시작 권한을
생성합니다. 산출물이 아직 없으므로 유닛을 활성화만 하고 시작하지는 않습니다.

## 4. 수동 백엔드 환경 파일 생성

백엔드 EC2의 `/home/ubuntu/ktb-chat-backend/.env`는 릴리스와 분리해 영구 보관합니다.
아래 값을 실제 운영 값으로 작성합니다.

```bash
cat > /home/ubuntu/ktb-chat-backend/.env <<'EOF'
ENCRYPTION_KEY=64자리_HEX_키
ENCRYPTION_SALT=솔트
JWT_SECRET=충분히_긴_랜덤_시크릿
MONGO_URI=mongodb://MONGODB_PRIVATE_HOST:27017/bootcamp-chat
REDIS_HOST=REDIS_PRIVATE_HOST
REDIS_PORT=6379
REDIS_PASSWORD=실제_비밀번호
PORT=5001
WS_PORT=5002
OPENAI_API_KEY=실제_OpenAI_API_키
CORS_ALLOWED_ORIGINS=https://chat.goorm-ktb-007.goorm.team
SOCKETIO_SERVER_ORIGIN=https://chat.goorm-ktb-007.goorm.team
EOF

chmod 600 /home/ubuntu/ktb-chat-backend/.env
```

키는 예를 들어 다음과 같이 생성할 수 있습니다.

```bash
openssl rand -hex 32
openssl rand -hex 64
```

MongoDB와 Redis 접속은 해당 백엔드 EC2에서 미리 확인해야 합니다. 운영 비밀값을
저장소나 GitHub Actions 로그에 출력하지 않습니다.

## 5. 설정 검증

각 EC2에서 실행합니다.

```bash
sudo systemd-analyze verify /etc/systemd/system/ktb-frontend.service
sudo systemd-analyze verify /etc/systemd/system/ktb-backend.service
sudo systemctl cat ktb-frontend.service
sudo systemctl cat ktb-backend.service
```

역할에 없는 유닛의 `verify`/`cat` 명령은 생략합니다. 첫 배포 전에는 `current`
심볼릭 링크와 산출물이 없으므로 서비스를 직접 시작하지 않습니다.

## 6. 첫 배포 테스트

워크플로우를 `main` 브랜치에 반영한 후 GitHub 저장소의 **Actions → Build and
deploy → Run workflow**에서 먼저 `frontend`, 다음으로 `backend`를 실행합니다.

배포 후 EC2 내부 확인:

```bash
systemctl status ktb-frontend.service --no-pager
curl -fsS http://localhost:3000/ >/dev/null

systemctl status ktb-backend.service --no-pager
curl -fsS http://localhost:5001/api/health
```

외부 ALB 확인:

```bash
curl -fsS https://chat.goorm-ktb-007.goorm.team/ >/dev/null
curl -fsS https://chat.goorm-ktb-007.goorm.team/api/health
```

실패 로그:

```bash
journalctl -u ktb-frontend.service -n 100 --no-pager
journalctl -u ktb-backend.service -n 100 --no-pager
```
