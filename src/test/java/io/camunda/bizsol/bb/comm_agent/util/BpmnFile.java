package io.camunda.bizsol.bb.comm_agent.util;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Test utility for loading BPMN files and performing string-level replacements on them before
 * deployment. Useful for overriding property values (e.g. endpoints, model names) in {@code
 * zeebe:input} mappings without depending on external tools.
 */
public class BpmnFile {

    private BpmnFile() {}

    /**
     * Read a BPMN model from a file-system path or classpath resource.
     *
     * @param location the BPMN file-system path or classpath resource path
     * @return the parsed model instance
     */
    public static BpmnModelInstance read(String location) {
        try (InputStream inputStream = openStream(location)) {
            return Bpmn.readModelFromStream(inputStream);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Read a BPMN model from a file.
     *
     * @param file the BPMN file to read
     * @return the parsed model instance
     */
    public static BpmnModelInstance read(File file) {
        return read(file.getPath());
    }

    /**
     * Perform string-level replacements on a BPMN file and return the parsed model. Each {@link
     * Replace} is applied in order via {@link String#replace(CharSequence, CharSequence)}.
     *
     * <p>Example: swap the LLM endpoint for a WireMock URL before deploying:
     *
     * <pre>
     * BpmnFile.replace(bpmnFile,
     *     Replace.replace("http://localhost:11434/v1", "http://localhost:8089/v1"));
     * </pre>
     */
    public static BpmnModelInstance replace(String location, Replace... replaces) {
        try {
            String modelXml = new String(readAllBytes(location), StandardCharsets.UTF_8);
            for (var replace : replaces) {
                modelXml = modelXml.replace(replace.oldValue(), replace.newValue());
            }
            return Bpmn.readModelFromStream(
                    new ByteArrayInputStream(modelXml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static BpmnModelInstance replace(File file, Replace... replaces) {
        return replace(file.getPath(), replaces);
    }

    private static byte[] readAllBytes(String location) throws IOException {
        try (InputStream inputStream = openStream(location)) {
            return inputStream.readAllBytes();
        }
    }

    private static InputStream openStream(String location) throws IOException {
        File file = new File(location);
        if (file.isFile()) {
            return Files.newInputStream(file.toPath());
        }
        if (file.isAbsolute()) {
            throw new IOException("BPMN file not found: " + location);
        }

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = BpmnFile.class.getClassLoader();
        }

        String classpathLocation = normalizeClasspathLocation(location);
        InputStream inputStream = classLoader.getResourceAsStream(classpathLocation);
        if (inputStream != null) {
            return inputStream;
        }

        String fileName = extractFileName(classpathLocation);
        if (!fileName.equals(classpathLocation)) {
            inputStream = classLoader.getResourceAsStream(fileName);
            if (inputStream != null) {
                return inputStream;
            }
        }

        throw new IOException("BPMN resource not found as file or classpath resource: " + location);
    }

    private static String normalizeClasspathLocation(String location) {
        String normalized = location.replace(File.separatorChar, '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.startsWith("/")) {
            return normalized.substring(1);
        }
        return normalized;
    }

    private static String extractFileName(String location) {
        int lastSlash = location.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < location.length() - 1) {
            return location.substring(lastSlash + 1);
        }
        return location;
    }

    public record Replace(String oldValue, String newValue) {
        public static Replace replace(String oldValue, String newValue) {
            return new Replace(oldValue, newValue);
        }
    }
}
