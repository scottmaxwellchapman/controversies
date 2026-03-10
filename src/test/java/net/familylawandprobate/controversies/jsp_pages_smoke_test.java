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

        JspC jspc = new JspC();
        jspc.setCompile(true);
        jspc.setFailOnError(true);
        jspc.setListErrors(true);
        jspc.setClassPath(System.getProperty("java.class.path"));
        jspc.setUriroot(webRoot.toString());
        jspc.setOutputDir(outputDir.toString());
        jspc.setCompilerSourceVM("17");
        jspc.setCompilerTargetVM("17");
        jspc.execute();

        assertTrue(
                Files.isDirectory(outputDir),
                "Expected JSP compiler output directory to be created."
        );
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
