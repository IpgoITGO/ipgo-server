"""실제 FastAPI 라우트/검사기와 FakeClient만 실행하는 로컬 통합 시험용 서버.

ipgo-ai checkout의 app/insight/schemas와 함께 내부 Docker network에서 사용한다.
운영 배포용이 아니다. 외부 LLM 호출기를 dependency override로 대체한다.
"""
import json

import uvicorn
from app.main import app
from app.routers.insight import get_llm_client
from insight.llm import FakeClient

DRAFT = json.dumps({"sentences": [
    "{{qty_p100}}개를 넣으면 다음 입고 때 {{ending_p100}}개가 남고 도착 후 품절 위험은 {{risk_basis}} 추정치로 {{risk_post_p100}}입니다.",
    "새 물량이 도착하기 전까지의 품절 위험은 {{risk_pre}}입니다.",
]}, ensure_ascii=False)
app.dependency_overrides[get_llm_client] = lambda: FakeClient([DRAFT])

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000, access_log=False)
