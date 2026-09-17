package dev.wareworks.dev;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The shots of a run, written to {@code <gameDir>/screenshots/index.txt} at the end (client thread). */
final class VisualShotIndex {
    /** One screenshot: number, file name, label and the status logged when it was taken. */
    record Entry(int number, String fileName, String label, String status) {
    }

    private final List<Entry> entries = new ArrayList<>();

    void add(Entry entry) {
        entries.add(entry);
    }

    int size() {
        return entries.size();
    }

    /** Writes the index: result, shots, then the sound events the client played during the run with their counts. */
    void write(Path file, String scenario, String result, Map<String, Integer> sounds) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# Create: Wareworks visual smoke test");
        lines.add("scenario: " + scenario);
        lines.add("result: " + result);
        lines.add("shots: " + entries.size());
        for (Entry entry : entries)
            lines.add(String.format(Locale.ROOT, "%02d  %s  %s  %s", entry.number(), entry.fileName(), entry.label(),
                    entry.status()));
        lines.add("sounds: " + sounds);
        Files.createDirectories(file.getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
