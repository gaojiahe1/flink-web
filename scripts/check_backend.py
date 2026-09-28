"""检查后端分层及中文 Javadoc 覆盖；只读源码，不修改代码。"""
from pathlib import Path
import argparse,base64,os,re,subprocess,sys
root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("--java",default=str(Path(os.environ["JAVA_HOME"])/"bin"/"java") if "JAVA_HOME" in os.environ else "java")
args=parser.parse_args()
outputs=[]
for source in ["backend/src","sql-runner/src"]:
 result=subprocess.run([args.java,"-Dfile.encoding=UTF-8",str(root/"scripts/JavaDocInventory.java"),str(root/source)],capture_output=True,text=True,encoding="utf-8")
 if result.returncode:
  print(result.stderr);sys.exit(result.returncode)
 outputs.extend(result.stdout.splitlines())
errors=[];classes=methods=0
for line in outputs:
 path,pos,kind,owner,name,encoded=line.split("\t")
 doc=base64.b64decode(encoded).decode("utf-8")
 if not re.search(r"[\u4e00-\u9fff]",doc):
  errors.append(f"Missing Chinese Javadoc: {path} {owner}.{name}")
 if kind=="class": classes+=1
 else: methods+=1
base=root/"backend/src/main/java/com/flinkweb"
layers={"client","collector","config","controller","datasource","dto","entity","exception","repository","security","service"}
for path in base.rglob("*.java"):
 text=path.read_text(encoding="utf-8");relative=path.relative_to(base)
 layer=relative.parts[0] if len(relative.parts)>1 else ""
 if layer and layer not in layers: errors.append(f"Unexpected package: {relative}")
 if re.search(r"com\.flinkweb\.(module|common|integration)\.",text): errors.append(f"Old package: {relative}")
 if layer=="controller" and re.search(r"com\.flinkweb\.(repository|client|collector|datasource)\.|JdbcTemplate",text):
  errors.append(f"Controller bypasses service: {relative}")
 if layer not in {"repository","datasource"} and ("JdbcTemplate" in text or re.search(r'"(?:SELECT |INSERT INTO |UPDATE \w+ SET |DELETE FROM |CREATE TABLE )',text)):
  errors.append(f"SQL outside persistence layer: {relative}")
 if layer=="client" and re.search(r"com\.flinkweb\.(service|repository)\.",text):
  errors.append(f"Client depends on business/persistence layer: {relative}")
 if layer and len(relative.parts)<3: errors.append(f"Functional subpackage missing: {relative}")
 if layer=="service" and path.stem.endswith("Service") and ("public interface "+path.stem) not in text:
  errors.append(f"Service contract must be an interface: {relative}")
 if path.stem.endswith("ServiceImpl") and path.parent.name!="impl":
  errors.append(f"Service implementation outside impl: {relative}")
 if layer=="controller" and any(".impl." in line for line in text.splitlines() if line.startswith("import com.flinkweb.service.")):
  errors.append(f"Controller depends on concrete service implementation: {relative}")
 if "@Scheduled" in text or "scheduleAtFixedRate(" in text: errors.append(f"Background schedule found: {relative}")
if errors:
 print("\n".join(errors));sys.exit(1)
print(f"PASS: {classes} classes and {methods} methods/constructors have Chinese Javadoc; architecture boundaries verified.")
