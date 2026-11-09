"""-I로 환경/PYTHONPATH를 무시하고 이미지의 고정 코드만 실행한다."""
import runpy
import sys

sys.path.insert(0, '/opt/scripts/d0')
runpy.run_path('/opt/scripts/d0/runtime.py', run_name='__main__')
