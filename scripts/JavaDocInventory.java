import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** 使用 JDK 语法树列出显式类与方法，供中文注释覆盖检查使用。 */
public class JavaDocInventory {
    /** 输出文件、位置、类型、所属类、名称及已有文档；不解析项目外部依赖。 */
    public static void main(String[] args) throws Exception {
        var compiler=ToolProvider.getSystemJavaCompiler();
        try(var manager=compiler.getStandardFileManager(null,null,StandardCharsets.UTF_8)) {
            var files=Files.walk(Path.of(args[0])).filter(p->p.toString().endsWith(".java")).map(Path::toFile).toList();
            var task=(JavacTask)compiler.getTask(null,manager,d->{},List.of("-proc:none"),null,manager.getJavaFileObjectsFromFiles(files));
            var units=task.parse();var trees=Trees.instance(task);
            for(var unit:units) {
                String path=Path.of(unit.getSourceFile().toUri()).toString();
                new TreePathScanner<Void,String>() {
                    /** 记录类、接口、枚举及记录类的声明。 */
                    @Override public Void visitClass(ClassTree tree,String owner) {
                        String name=tree.getSimpleName().toString();
                        String full=owner==null?name:owner+"."+name;
                        if(!name.isEmpty()) output(tree,"class",full,name);
                        return super.visitClass(tree,full);
                    }
                    /** 记录构造器和显式方法，忽略编译器隐式生成的成员。 */
                    @Override public Void visitMethod(MethodTree tree,String owner) {
                        output(tree,"method",owner,tree.getName().toString());
                        return super.visitMethod(tree,owner);
                    }
                    /** 用制表符分隔声明元信息，注释以 Base64 编码以避免换行干扰。 */
                    void output(Tree tree,String kind,String owner,String name) {
                        long start=trees.getSourcePositions().getStartPosition(unit,tree);
                        String doc=trees.getDocComment(getCurrentPath());
                        System.out.println(path+"\t"+start+"\t"+kind+"\t"+owner+"\t"+name+"\t"+
                            Base64.getEncoder().encodeToString((doc==null?"":doc).getBytes(StandardCharsets.UTF_8)));
                    }
                }.scan(unit,null);
            }
        }
    }
}
