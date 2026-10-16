"""一次性将已有片段从 Qwen3-Embedding-8B/4096 维迁移到 0.6B/1024 维。

默认只准备新向量；停止 Java 应用后用 --apply 指定准备目录，才切换数据库。
使用 Python 标准库和已有 docker 命令，不增加业务接口或 Python 依赖。
"""

import argparse
from datetime import datetime
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, getproxies, urlopen


OLD_MODEL = 'Qwen/Qwen3-Embedding-8B'
NEW_MODEL = 'Qwen/Qwen3-Embedding-0.6B'
NEW_DIMENSION = 1024
ROOT = Path('D:/develop/huiAnswer/data/embedding-migrations')


def setting(name, default=''):
    value = os.getenv(name)
    if value and value.strip():
        return value.strip()
    if os.name == 'nt':
        import winreg
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, 'Environment') as registry:
                value, _ = winreg.QueryValueEx(registry, name)
            return str(value).strip() or default
        except FileNotFoundError:
            pass
    return default


def save_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def psql(args, sql):
    command = ['docker', 'exec', '-i', args.container, 'psql', '-U', args.db_user,
               '-d', args.database, '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1']
    result = subprocess.run(command, input=sql.encode('utf-8'), capture_output=True, timeout=60)
    if result.returncode:
        detail = result.stderr.decode('utf-8', errors='replace').splitlines()
        raise RuntimeError('PostgreSQL 操作失败：' + (detail[0] if detail else '请检查容器'))
    return result.stdout.decode('utf-8').strip()


def vector_text(vector):
    if len(vector) != NEW_DIMENSION or any(not math.isfinite(v) for v in vector):
        raise ValueError('模型返回了错误维度或非法向量')
    if not any(v != 0 for v in vector):
        raise ValueError('不能用零向量做余弦检索')
    return '[' + ','.join(str(v) for v in vector) + ']'


def request_vectors(base_url, key, texts):
    url = base_url + ('/embeddings' if base_url.endswith('/v1') else '/v1/embeddings')
    body = {'model': NEW_MODEL, 'input': texts, 'encoding_format': 'float', 'dimensions': NEW_DIMENSION}
    request = Request(url, data=json.dumps(body, ensure_ascii=False).encode('utf-8'),
                      headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
    try:
        with urlopen(request, timeout=60) as response:
            payload = json.load(response)
    except HTTPError as error:
        raise RuntimeError(f'Embedding 请求失败：HTTP {error.code}，数据库未切换') from None
    if payload.get('model') != NEW_MODEL:
        raise ValueError('Embedding 接口返回的模型与目标模型不一致')
    data = payload.get('data', [])
    if sorted(item.get('index', -1) for item in data) != list(range(len(texts))):
        raise ValueError('Embedding 返回的 index 缺失、重复或越界')
    vectors = [item['embedding'] for item in sorted(data, key=lambda item: item['index'])]
    for vector in vectors:
        vector_text(vector)
    return vectors


def write_cutover_sql(directory, rows, backup_table):
    values = ',\n'.join(
        f"({int(row['id'])}, '{row['source_hash']}', '{vector_text(row['embedding'])}'::vector)"
        for row in rows
    )
    sql = f"""-- 已有片段保持 ID、原文、关键词、归属不变；只替换向量。
BEGIN;
SET LOCAL lock_timeout = '5s';
LOCK TABLE public.t_knowledge_chunk IN ACCESS EXCLUSIVE MODE;
CREATE TEMP TABLE reembedding_stage (
    id BIGINT PRIMARY KEY, source_hash TEXT NOT NULL, embedding vector(1024) NOT NULL
) ON COMMIT DROP;
INSERT INTO reembedding_stage VALUES
{values};
DO $$ BEGIN
    IF (SELECT count(*) FROM public.t_knowledge_chunk) <> {len(rows)} OR EXISTS (
        SELECT 1 FROM public.t_knowledge_chunk c
        LEFT JOIN reembedding_stage s ON s.id = c.id
        WHERE s.id IS NULL OR md5(row_to_json(c)::text) <> s.source_hash
    ) THEN
        RAISE EXCEPTION '片段在准备期间发生变化，请重新准备，未切换数据库';
    END IF;
    IF (SELECT format_type(atttypid, atttypmod) FROM pg_attribute
        WHERE attrelid = 'public.t_knowledge_chunk'::regclass AND attname = 'embedding') <> 'vector(4096)' THEN
        RAISE EXCEPTION '当前数据库不是4096维，拒绝重复切换';
    END IF;
    IF EXISTS (SELECT 1 FROM public.t_knowledge_document WHERE deleted = 0 AND status IN ('pending', 'running')) THEN
        RAISE EXCEPTION '还有文档正在处理，请稍后切换';
    END IF;
END $$;
CREATE TABLE public.{backup_table} AS TABLE public.t_knowledge_chunk;
ALTER TABLE public.{backup_table} ADD PRIMARY KEY (id);
COMMENT ON TABLE public.{backup_table} IS '{OLD_MODEL}; 4096 dimensions; before 1024 migration';
-- 事务中暂时解除维度限制，更新全部向量，再恢复1024维约束；对外只暴露提交后的结果。
ALTER TABLE public.t_knowledge_chunk ALTER COLUMN embedding TYPE vector USING embedding::vector;
UPDATE public.t_knowledge_chunk c SET embedding = s.embedding FROM reembedding_stage s WHERE s.id = c.id;
ALTER TABLE public.t_knowledge_chunk ALTER COLUMN embedding TYPE vector(1024) USING embedding::vector(1024);
COMMENT ON COLUMN public.t_knowledge_chunk.embedding IS '{NEW_MODEL}; 1024 dimensions; documents and queries must use the same model';
COMMIT;
"""
    rollback = f"""-- 停止Java应用后执行；同时把应用模型和维度改回8B/4096。
-- 有新上传或修改时先重新制定回退方案，禁止用旧快照覆盖新的片段。
BEGIN;
SET LOCAL lock_timeout = '5s';
LOCK TABLE public.t_knowledge_chunk IN ACCESS EXCLUSIVE MODE;
DO $$ BEGIN
    IF EXISTS (
        SELECT 1 FROM public.t_knowledge_chunk c
        FULL JOIN public.{backup_table} b ON b.id = c.id
        WHERE c.id IS NULL OR b.id IS NULL
           OR (to_jsonb(c) - 'embedding') IS DISTINCT FROM (to_jsonb(b) - 'embedding')
    ) THEN
        RAISE EXCEPTION '片段内容已变化，拒绝直接回退';
    END IF;
END $$;
ALTER TABLE public.t_knowledge_chunk ALTER COLUMN embedding TYPE vector USING embedding::vector;
UPDATE public.t_knowledge_chunk c SET embedding = b.embedding FROM public.{backup_table} b WHERE b.id = c.id;
ALTER TABLE public.t_knowledge_chunk ALTER COLUMN embedding TYPE vector(4096) USING embedding::vector(4096);
COMMENT ON COLUMN public.t_knowledge_chunk.embedding IS '{OLD_MODEL}; 4096 dimensions';
COMMIT;
"""
    (directory / 'apply.sql').write_text(sql, encoding='utf-8')
    (directory / 'rollback.sql').write_text(rollback, encoding='utf-8')


def prepare(args):
    key = setting('HUIANSWER_AI_API_KEY')
    if not key:
        raise ValueError('请先设置 HUIANSWER_AI_API_KEY 用户环境变量')
    base_url = setting('HUIANSWER_AI_URL', 'https://api.siliconflow.cn').rstrip('/')
    if base_url not in ('https://api.siliconflow.cn', 'https://api.siliconflow.cn/v1'):
        raise ValueError('本次迁移仅使用已核实的原硅基流动地址')
    proxies = getproxies()
    if os.name == 'nt' and not os.getenv('HTTPS_PROXY') and not os.getenv('https_proxy'):
        hp, sp = proxies.get('http', ''), proxies.get('https', '')
        if hp and sp and urlsplit(hp).netloc == urlsplit(sp).netloc:
            os.environ['HTTPS_PROXY'] = hp
    snapshot = json.loads(psql(args, """
        SELECT json_build_object(
            'column_type', (SELECT format_type(atttypid, atttypmod) FROM pg_attribute
                           WHERE attrelid='public.t_knowledge_chunk'::regclass AND attname='embedding'),
            'inflight', (SELECT count(*) FROM t_knowledge_document WHERE deleted=0 AND status IN ('pending','running')),
            'rows', (SELECT json_agg(json_build_object('id',c.id,'content',c.content,'doc_name',d.doc_name,
                         'source_hash',md5(row_to_json(c)::text)) ORDER BY c.id)
                     FROM t_knowledge_chunk c JOIN t_knowledge_document d ON d.id=c.doc_id)
        );
    """))
    if snapshot['column_type'] != 'vector(4096)' or snapshot['inflight'] or not snapshot['rows']:
        raise ValueError('需要4096维、非空且没有正在处理文档的数据库')
    # 这次只迁移已经上传过的合成测试资料；发现额外内容时停止，不自动对外发送。
    fixture_roots = [Path(__file__).resolve().parents[1] / 'evaluation/xinglan-shop-v1/corpus',
                     Path('D:/develop/huiAnswer/checks/rag-qa-20261007')]
    sources = {p.name: p for root in fixture_roots for p in root.rglob('*.txt')}
    for row in snapshot['rows']:
        source = sources.get(row['doc_name'])
        if source is None or ''.join(row['content'].split()) not in ''.join(source.read_text(encoding='utf-8-sig').split()):
            raise ValueError(f"片段{row['id']}不属于本次已核实的合成测试资料，未发送")
    run_id = datetime.now().strftime('%Y%m%d_%H%M%S_%f')
    directory = ROOT / run_id
    directory.mkdir(parents=True, exist_ok=False)
    save_json(directory / 'source.json', snapshot)
    dump = subprocess.run(['docker', 'exec', args.container, 'pg_dump', '-U', args.db_user,
                           '-d', args.database, '-Fc', '-t', 'public.t_knowledge_chunk'],
                          capture_output=True, timeout=60)
    if dump.returncode:
        raise RuntimeError('片段表备份失败，未调用批量Embedding，数据库未切换')
    (directory / 'chunk-before.dump').write_bytes(dump.stdout)
    manifest = {'state': 'preparing', 'source_model': OLD_MODEL, 'source_dimension': 4096,
                'target_model': NEW_MODEL, 'target_dimension': NEW_DIMENSION,
                'chunk_count': len(snapshot['rows']), 'backup_table': 't_chunk_embedding_backup_' + run_id,
                'api_host': urlsplit(base_url).hostname, 'batches': [],
                'source_sha256': hashlib.sha256((directory / 'source.json').read_bytes()).hexdigest()}
    save_json(directory / 'manifest.json', manifest)
    rows = []
    for start in range(0, len(snapshot['rows']), 32):
        batch = snapshot['rows'][start:start + 32]
        began = time.perf_counter()
        vectors = request_vectors(base_url, key, [row['content'] for row in batch])
        rows.extend({'id': row['id'], 'source_hash': row['source_hash'], 'embedding': vector}
                    for row, vector in zip(batch, vectors))
        manifest['batches'].append({'count': len(batch), 'elapsed_ms': round((time.perf_counter()-began)*1000, 3)})
        save_json(directory / 'vectors-1024.json', rows)
        save_json(directory / 'manifest.json', manifest)
        print(f"Prepared {len(rows)}/{manifest['chunk_count']} chunks", flush=True)
    write_cutover_sql(directory, rows, manifest['backup_table'])
    manifest['apply_sha256'] = hashlib.sha256((directory / 'apply.sql').read_bytes()).hexdigest()
    manifest['vectors_sha256'] = hashlib.sha256((directory / 'vectors-1024.json').read_bytes()).hexdigest()
    manifest['state'] = 'prepared'
    save_json(directory / 'manifest.json', manifest)
    print('Prepared directory:', directory)
    print('Stop the Java application, then rerun with --apply and this directory. Database unchanged.')


def apply(args):
    directory = args.apply.resolve()
    manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
    if manifest['state'] != 'prepared' or not re.fullmatch(r't_chunk_embedding_backup_\d{8}_\d{6}_\d{6}', manifest['backup_table']):
        raise ValueError('准备状态不正确或已经切换')
    for filename, field in [('source.json', 'source_sha256'), ('vectors-1024.json', 'vectors_sha256'), ('apply.sql', 'apply_sha256')]:
        if hashlib.sha256((directory / filename).read_bytes()).hexdigest() != manifest[field]:
            raise ValueError('准备文件已被修改，拒绝切换')
    # psql遇到任何错误即退出，事务连接关闭后整体回滚；旧数据只在提交成功后才切换。
    print(psql(args, (directory / 'apply.sql').read_text(encoding='utf-8')))
    manifest['state'] = 'applied'
    manifest['applied_at'] = datetime.now().isoformat(timespec='seconds')
    save_json(directory / 'manifest.json', manifest)
    print('Applied. Old vectors remain in public.' + manifest['backup_table'])
    print('Start Java with ' + NEW_MODEL + ' / 1024. Rollback SQL:', directory / 'rollback.sql')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', type=Path, help='准备完成后指定该目录，切换数据库；须先停止Java应用')
    parser.add_argument('--container', default='postgres')
    parser.add_argument('--database', default='huianswer')
    parser.add_argument('--db-user', default='postgres')
    args = parser.parse_args()
    apply(args) if args.apply else prepare(args)


if __name__ == '__main__':
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    try:
        main()
    except Exception as error:
        # 不打印HTTP响应体、请求头或密钥。
        print(f'Migration stopped: {type(error).__name__}: {error}', file=sys.stderr)
        sys.exit(1)
