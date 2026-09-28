"""仅用于端到端测试的本地 Kubernetes 协议替身；不连接真实集群。"""
import json,sys
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
requests=[]
namespace="flink-jobs"
deployment={
 "apiVersion":"flink.apache.org/v1beta1","kind":"FlinkDeployment",
 "metadata":{"name":"existing-e2e-job","namespace":namespace,"uid":"existing-e2e-uid","resourceVersion":"42","labels":{"app.kubernetes.io/managed-by":"Helm"}},
 "spec":{"image":"registry.example/flink:1.20","flinkVersion":"v1_20","serviceAccount":"legacy-flink",
  "flinkConfiguration":{"state.checkpoints.num-retained":"5"},
  "job":{"jarURI":"local:///opt/flink/usrlib/existing.jar","parallelism":2,"state":"running","upgradeMode":"savepoint"}},
 "status":{"lifecycleState":"STABLE","jobStatus":{"state":"RUNNING"}}}
base="/apis/flink.apache.org/v1beta1/namespaces/"+namespace
class Handler(BaseHTTPRequestHandler):
 def reply(self,status,body):
  encoded=json.dumps(body).encode();self.send_response(status);self.send_header("Content-Type","application/json");self.end_headers();self.wfile.write(encoded)
 def do_GET(self):
  if self.path=="/__requests": return self.reply(200,requests)
  requests.append({"method":"GET","path":self.path})
  if self.path=="/apis/apps/v1/namespaces/"+namespace+"/deployments/flink-kubernetes-operator":
   return self.reply(200,{"metadata":{"generation":1},"spec":{"replicas":1,"selector":{"matchLabels":{"app":"flink-kubernetes-operator"}},"template":{"spec":{"containers":[{"name":"flink-kubernetes-operator","image":"apache/flink-kubernetes-operator:1.12.0"}]}}},"status":{"readyReplicas":1,"observedGeneration":1}})
  if self.path.startswith("/api/v1/namespaces/"+namespace+"/pods?labelSelector="):
   return self.reply(200,{"items":[{"metadata":{"name":"operator-e2e"},"status":{"phase":"Running","conditions":[{"type":"Ready","status":"True"}],"containerStatuses":[{"name":"flink-kubernetes-operator","ready":True,"restartCount":0,"state":{"running":{}}}]}}]})
  if self.path==base+"/flinkdeployments": return self.reply(200,{"items":[deployment]})
  if self.path==base+"/flinkdeployments/existing-e2e-job": return self.reply(200,deployment)
  self.reply(404,{"kind":"Status","code":404})
 def reject_write(self):
  requests.append({"method":self.command,"path":self.path});self.reply(405,{"code":405})
 do_POST=do_PUT=do_PATCH=do_DELETE=reject_write
 def log_message(self,*args): pass
if __name__=="__main__":
 ThreadingHTTPServer(("127.0.0.1",int(sys.argv[1]) if len(sys.argv)>1 else 18765),Handler).serve_forever()
