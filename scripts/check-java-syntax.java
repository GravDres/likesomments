// Run with: java scripts/check-java-syntax.java (JDK 17+).
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;

class CheckJavaSyntax {
    public static void main(String[] args)throws Exception{
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        if(compiler==null)throw new IllegalStateException("JDK compiler required");
        List<java.io.File> files=new ArrayList<>();
        try(var walk=Files.walk(Path.of("app/src/main/java"))){walk.filter(p->p.toString().endsWith(".java")).forEach(p->files.add(p.toFile()));}
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        try(var manager=compiler.getStandardFileManager(diagnostics,null,null)){
            JavacTask task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none"),null,manager.getJavaFileObjectsFromFiles(files));
            task.parse();
            for(var d:diagnostics.getDiagnostics())if(d.getKind()==Diagnostic.Kind.ERROR)throw new AssertionError(d.toString());
        }
        System.out.println("PASS: Java syntax parsed for "+files.size()+" application files (Android type checking requires SDK / Actions)");
    }
}
