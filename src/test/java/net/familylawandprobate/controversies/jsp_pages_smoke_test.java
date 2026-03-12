package net.familylawandprobate.controversies;

import org.apache.jasper.JspC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class jsp_pages_smoke_test {

    private Path outputDir;

    @AfterEach
    void cleanup() throws Exception {
        if (outputDir == null) {
            return;
        }
        if (!Files.exists(outputDir)) {
            outputDir = null;
            return;
        }
        try (var walk = Files.walk(outputDir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        }
        outputDir = null;
    }

    @Test
    void all_jsp_pages_compile_without_translation_errors() throws Exception {
        Path webRoot = Path.of("src", "main", "webapp").toAbsolutePath().normalize();
        List<String> routes = collectJspRoutes(webRoot);
        assertTrue(!routes.isEmpty(), "Expected at least one JSP page in webapp.");
        outputDir = Files.createTempDirectory("jspc-smoke-");
        String compilerVm = detectCompilerVm();

        JspC jspc = new JspC();
        jspc.setCompile(true);
        jspc.setFailOnError(true);
        jspc.setListErrors(true);
        jspc.setCompiler("modern");
        jspc.setThreadCount("1");
        jspc.setClassPath(System.getProperty("java.class.path"));
        jspc.setUriroot(webRoot.toString());
        jspc.setOutputDir(outputDir.toString());
        jspc.setCompilerSourceVM(compilerVm);
        jspc.setCompilerTargetVM(compilerVm);
        jspc.execute();

        assertTrue(
                Files.isDirectory(outputDir),
                "Expected JSP compiler output directory to be created."
        );
    }

    private static String detectCompilerVm() {
        String override = System.getProperty("CONTROVERSIES_JSPC_VM");
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        Path classesRoot = Path.of("target", "classes").toAbsolutePath().normalize();
        if (!Files.isDirectory(classesRoot)) {
            return "17";
        }
        try (var stream = Files.walk(classesRoot)) {
            Path classFile = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .findFirst()
                    .orElse(null);
            if (classFile == null) {
                return "17";
            }
            byte[] header = new byte[8];
            try (var in = Files.newInputStream(classFile)) {
                if (in.read(header) != header.length) {
                    return "17";
                }
            }
            int major = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
            int release = major - 44;
            if (release >= 8 && release <= 30) {
                return Integer.toString(release);
            }
        } catch (Exception ignored) {
        }
        return "17";
    }

    private static List<String> collectJspRoutes(Path webRoot) throws Exception {
        if (!Files.isDirectory(webRoot)) {
            throw new IllegalStateException("Web root not found: " + webRoot);
        }

        ArrayList<String> routes = new ArrayList<String>();
        try (var stream = Files.walk(webRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jsp"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> {
                        Path rel = webRoot.relativize(path);
                        String relPath = rel.toString().replace(File.separatorChar, '/');
                        if (relPath.startsWith("WEB-INF/")) {
                            return;
                        }
                        routes.add("/" + relPath);
                    });
        }
        return routes;
    }
}
