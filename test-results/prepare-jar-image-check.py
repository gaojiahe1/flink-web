from pathlib import Path
root = Path(__file__).resolve().parents[1]
source = (root/'test-results/operator-pod-image-check.py').read_text(encoding='utf-8')
source = source.replace("'tests/pod-logs.spec.ts', '--output=test-results/image-pod-check'", "'tests/artifact-programs.spec.ts', 'tests/pod-logs.spec.ts', '--output=test-results/image-jar-check'")
source = source.replace('built Pod/log UI regressions', 'built artifact, Runner, entry-class and Pod/log UI regressions')
(root/'test-results/jar-program-image-check.py').write_text(source,encoding='utf-8')
