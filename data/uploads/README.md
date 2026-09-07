# data/uploads (소유: 정세현)

업로드된 상품문서(F-EXT-001 · 이슈 #521)가 사는 곳. **여기 있는 파일은 git 에 안 들어간다**
— 이 README 만 추적한다.

## ❗빈 디렉토리를 커밋해 두는 이유 — 지우면 ai-service 가 기동하지 않는다

배포에서 실제 파일은 호스트 트리가 아니라 **이름 있는 도커 볼륨**(`sphinx_uploads`)에 산다.
호스트 트리가 배포마다 새 디렉토리라(`mktemp -d -p /opt`) 거기 쓴 업로드는 다음 배포에서
사라지기 때문이다. 커밋된 원본 2종은 git 이 매 회차 다시 가져오지만 업로드본은 아무도
다시 만들어 주지 않는다.

그런데 `ai-service` 는 `./data:/data:ro` 로 `data/` 를 **읽기 전용**으로 통째 마운트하고,
그 위에 `/data/uploads` 를 겹쳐 붙인다. Docker 는 마운트 지점이 없으면 **만들려고 하는데
부모가 읽기 전용이라 실패하고, 컨테이너가 아예 뜨지 않는다.**

```
실측:
  data/uploads 없음  →  error mounting … create mountpoint for /data/uploads:
                        read-only file system            ← ai-service 기동 실패
  data/uploads 있음  →  /data/timeseries/VERSION(고정 코퍼스) · /data/uploads/…(업로드본)
                        둘 다 읽힌다
                        /data/uploads 쓰기는 거부된다     ← 의도대로
```

즉 이 디렉토리는 **호스트에 있어야 하는 빈 마운트 지점**이다. 볼륨이 그 위에 덮이므로
배포에서는 안이 보이지 않는다.

## 고정 코퍼스와 섞이지 않는다

같은 `data/` 아래 있지만 성격이 다르다. `timeseries/`·`documents/` 는 `VERSION` 의
sha256 으로 고정한 재현성의 입력이고(결정 7.8 · P2), 여기는 사람이 올린 것이다. 배포에서
둘이 **다른 저장소에 산다** — 앞의 것은 git 트리, 이쪽은 도커 볼륨. 그래서 고정 코퍼스의
sha256 이 덮는 범위가 이것 때문에 흔들리지 않는다.

## 왜 `/data` 밖으로 빼지 않았나

`ai-service` 가 문서를 읽어도 되는 뿌리가 `SPHINX_DATA_DIR` 하나이고
(`parsing.py` `documents_root()`), `resolve_document_path` 가 그 밖의 경로를 파일을
만지기 전에 거부한다. 밖으로 빼면 그 방어를 고쳐야 하는데, 같은 함수의 주석이
*"전용 환경변수를 새로 만들지 않는다 — 결정 전에 knob 을 박으면 결정이 그 knob 에
맞춰진다"* 로 그것을 이미 막아 두었다. 안에 두면 `document_path` 가
`uploads/<sha256>.pdf` 로 `documents/…` 와 같은 문법이 되고 계약이 하나도 안 바뀐다.

## 레이아웃은 `uploads/<sha256>/<정제한 파일명>`

디렉토리가 내용 주소(sha256)이고 **파일명은 올린 것을 정제해서 살린다.** 경로 결정에서
이름을 뺀 것이 요점이라, 파일명이 경로를 정하지 않는다.

처음에 `<sha256>.pdf` 로 제안했다가 되돌렸다(`#527` 리뷰 교차). **결정적인 것은 이 볼륨이
복구 수단이 없는 유일한 자산이라는 사실이다** — `external: true` 를 고른 것과 같은 근거인데,
같은 사실이 파일명에도 걸린다. DB 를 잃고 볼륨만 남은 상황에서 `9f2a….pdf` 만 있는 트리는
**어느 파일이 무엇인지 아무도 모른다.** 「무엇을 올렸는가」가 볼륨 안에 남아야 한다.

해시를 디렉토리로 올려도 원래 노린 것은 다 성립한다.

1. **경로 조작이 막힌다.** `safeFilename` 이 경로 구분자·`..`·제어문자를 걷고,
   `ProductDocuments.resolveWithin` 이 뿌리 밖을 파일을 만지기 전에 거부한다(이중 방어).
2. **같은 문서 재업로드가 한 벌이다.** 디렉토리가 해시라 같은 내용은 같은 자리다.
3. **무결성 대조가 공짜다.** `GET /products/{id}/document`(#412)가 낸 파일이 추출에 쓴 그
   파일인지 경로만으로 답한다 — `evidence/` 가 `contentHash` 로 하는 것과 같은 문법이다.

## ❗쓰는 것은 server 하나이고, 볼륨 소유자가 걸린다

`server` 컨테이너는 비루트(uid 10001)로 돈다. `docker volume create` 는 볼륨 루트를
`root:root 0755` 로 만들므로, 이미지가 소유자를 박아 두지 않으면 **업로드가 EACCES 로
500 을 낸다.** `server/Dockerfile` 의 `mkdir -p /data/uploads && chown sphinx:sphinx` 가
그 자리다 — Docker 가 **빈** 볼륨에 이미지의 소유권을 복사하는 성질에 얹혀 있다.

**이미 파일이 든 볼륨은 그 방법으로 안 고쳐진다.** 그때는 손으로 고친다.

```
docker run --rm -v sphinx_uploads:/v alpine chown -R 10001:999 /v
```

`ai-service` 는 같은 지점을 `:ro` 로 붙어서 읽기만 한다 — 파서가 자기 입력을 고칠 수
있으면 재현성(P2)의 전제가 깨진다.
