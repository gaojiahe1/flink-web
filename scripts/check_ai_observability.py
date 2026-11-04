"""显式现场验证：Java OTLP直连/临时Collector -> Langfuse，使用合成数据且不输出凭据。"""
import base64,json,os,re,subprocess,shutil,time,urllib.request,urllib.error,uuid
from pathlib import Path
root=Path(__file__).resolve().parents[1]
base=os.environ.get("LANGFUSE_BASE_URL","http://127.0.0.1:3000").rstrip("/")
public=os.environ.get("LANGFUSE_PUBLIC_KEY","")
secret=os.environ.get("LANGFUSE_SECRET_KEY","")
if not public or not secret: raise SystemExit("需要环境变量LANGFUSE_PUBLIC_KEY和LANGFUSE_SECRET_KEY")
auth="Basic "+base64.b64encode((public+":"+secret).encode()).decode()
def get(path):
    req=urllib.request.Request(base+path,headers={"Authorization":auth})
    with urllib.request.urlopen(req,timeout=10) as response:return json.load(response)
def docker(*args):
    return subprocess.check_output(["docker",*args],text=True,stderr=subprocess.DEVNULL).strip()
name="flink-web-otel-qa-"+uuid.uuid4().hex[:8]
results=[]
try:
    networks=json.loads(docker("inspect","langfuse-langfuse-web-1","--format","{{json .NetworkSettings.Networks}}"))
    network=next(iter(networks))
    env=os.environ.copy();env["LANGFUSE_OTLP_BASE_URL"]="http://langfuse-langfuse-web-1:3000/api/public/otel"
    command=["docker","run","-d","--name",name,"--network",network,"-p","127.0.0.1::4318","-e","LANGFUSE_PUBLIC_KEY","-e","LANGFUSE_SECRET_KEY","-e","LANGFUSE_OTLP_BASE_URL","--mount","type=bind,source="+str(root/"deploy/observability/otel-collector.yaml")+",target=/etc/otelcol/config.yaml,readonly","otel/opentelemetry-collector-contrib:0.164.0","--config=/etc/otelcol/config.yaml"]
    subprocess.run(command,env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
    port=docker("port",name,"4318/tcp").rsplit(":",1)[1]
    env["FLINK_TEST_LIVE_LANGFUSE"]="true";env["LANGFUSE_BASE_URL"]=base
    if env.get("JAVA_HOME"):env["PATH"]=str(Path(env["JAVA_HOME"])/"bin")+os.pathsep+env["PATH"]
    maven=shutil.which("mvn.cmd" if os.name=="nt" else "mvn",path=env["PATH"])
    if not maven:raise RuntimeError("需要Java17及Maven，请配置JAVA_HOME和PATH")
    for mode in ["direct","collector"]:
        env["FLINK_WEB_AI_TELEMETRY_MODE"]=mode;env["FLINK_WEB_OTEL_TRACES_ENDPOINT"]="http://127.0.0.1:"+port+"/v1/traces"
        run=subprocess.run([maven,"-q","-f",str(root/"backend/pom.xml"),"-Dtest=AiTelemetryIntegrationTest#liveLangfuse","test"],cwd=root,env=env,capture_output=True,text=True,encoding="utf-8",errors="replace")
        match=re.search(r"LANGFUSE_QA_TRACE "+mode+r" ([0-9a-f]{32})",run.stdout)
        if run.returncode or not match: raise RuntimeError("Java OTLP验证未通过，模式="+mode)
        trace=match.group(1);observations=[]
        for attempt in range(40):
            try:
                payload=get("/api/public/v2/observations?traceId="+trace+"&limit=100&fields=core,basic,model,usage")
                observations=[row for row in payload.get("data",[]) if row.get("traceId")==trace]
                if len(observations)>=3:break
            except (urllib.error.HTTPError,urllib.error.URLError,TimeoutError):pass
            time.sleep(1)
        if len(observations)<3:raise RuntimeError("Langfuse尚未查到完整观测，模式="+mode+" trace="+trace)
        types={row.get("type","").lower() for row in observations}
        if not {"chain","generation","tool"}.issubset(types):raise RuntimeError("观测类型不完整，模式="+mode)
        root_span=next(row for row in observations if row.get("type","").lower()=="chain")
        generation=next(row for row in observations if row.get("type","").lower()=="generation")
        if any(row.get("parentObservationId")!=root_span["id"] for row in observations if row["id"]!=root_span["id"]):raise RuntimeError("父子关系不正确，模式="+mode)
        usage=generation.get("usageDetails",{})
        if generation.get("model")!="test-model" or usage.get("input")!=12 or usage.get("output")!=3:raise RuntimeError("模型或Token用量不正确，模式="+mode)
        results.append({"usage":usage,"mode":mode,"traceId":trace,"observations":len(observations),"types":sorted(types)})
    print(json.dumps(results,ensure_ascii=False))
finally:
    subprocess.run(["docker","rm","-f",name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
