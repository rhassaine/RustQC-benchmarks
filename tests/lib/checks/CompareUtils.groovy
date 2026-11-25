/**
 * Shared comparison utilities for RustQC benchmark nf-tests.
 *
 * Provides methods for comparing RustQC outputs against upstream tool
 * reference snapshots with configurable tolerance and filtering.
 *
 * Lives in the `checks` package so the per-tool checks in this directory can use it
 * (a packaged Groovy class cannot reference a class in the default package).
 * tests/lib/CompareUtils.groovy re-exports it under its old, unqualified name.
 */
package checks

class CompareUtils {

    /**
     * Compare two TSV/tabular files line-by-line with configurable rules.
     *
     * @param actual    Lines from the RustQC output file
     * @param expected  Lines from the upstream reference snapshot
     * @param opts      Map with optional keys:
     *   - tolerance     (double):        absolute numeric tolerance (default: 0.0 = exact)
     *   - relTolerance  (double):        relative numeric tolerance (default: 0.0 = unused)
     *                                    When both tolerance and relTolerance are set, a value
     *                                    passes if it satisfies EITHER check (lenient mode).
     *   - skipPrefixes  (List<String>):  skip lines starting with these prefixes
     *   - skipColumns   (Set<Integer>):  column indices to ignore in comparison
     *   - trimLines     (boolean):       trim whitespace from lines (default: true)
     *   - delimiter     (String):        column delimiter (default: '\t')
     *   - skipBlank     (boolean):       skip blank lines (default: true)
     *   - maxErrors     (int):           max mismatches to report (default: 20)
     *
     * @throws AssertionError with detailed mismatch report
     */
    static void tsvMatch(List<String> actual, List<String> expected, Map opts = [:]) {
        double tolerance    = opts.tolerance    ?: 0.0
        double relTolerance = opts.relTolerance ?: 0.0
        List<String> skipPrefixes = opts.skipPrefixes ?: []
        Set<Integer> skipColumns  = (opts.skipColumns ?: []) as Set
        boolean trimLines = opts.containsKey('trimLines') ? opts.trimLines : true
        String delimiter  = opts.delimiter ?: '\t'
        boolean skipBlank = opts.containsKey('skipBlank') ? opts.skipBlank : true
        int maxErrors     = opts.maxErrors ?: 20

        // Pre-process lines
        actual   = prepareLines(actual, skipPrefixes, trimLines, skipBlank)
        expected = prepareLines(expected, skipPrefixes, trimLines, skipBlank)

        assert actual.size() == expected.size() :
            "Line count mismatch: actual=${actual.size()}, expected=${expected.size()}\n" +
            "First divergence around line ${Math.min(actual.size(), expected.size())}"

        List<String> errors = []

        actual.eachWithIndex { line, i ->
            def aCols = line.split(delimiter, -1)
            def eCols = expected[i].split(delimiter, -1)

            if (aCols.length != eCols.length) {
                errors << "Line ${i}: column count ${aCols.length} vs ${eCols.length}"
                return
            }

            aCols.eachWithIndex { val, j ->
                if (j in skipColumns) return

                // Try numeric comparison if tolerance is set
                if (tolerance > 0 || relTolerance > 0) {
                    try {
                        // Treat NA/NaN as 0 for numeric comparison (common in R output)
                        def aStr = val.trim().toUpperCase()
                        def eStr = eCols[j].trim().toUpperCase()
                        double aNum = (aStr == 'NA' || aStr == 'NAN') ? 0.0 : val.toDouble()
                        double eNum = (eStr == 'NA' || eStr == 'NAN') ? 0.0 : eCols[j].toDouble()

                        // Exact match — always pass (handles 0.0 vs 0.0 edge case)
                        if (aNum == eNum) return

                        boolean withinAbsolute = (tolerance > 0) ? Math.abs(aNum - eNum) <= tolerance : false
                        boolean withinRelative = false
                        if (relTolerance > 0 && eNum != 0.0) {
                            withinRelative = Math.abs((aNum - eNum) / eNum) <= relTolerance
                        }

                        // Pass if EITHER tolerance is satisfied (lenient when both set)
                        if (!withinAbsolute && !withinRelative) {
                            def details = []
                            if (tolerance > 0) details << "abs diff ${Math.abs(aNum - eNum)}, tolerance ${tolerance}"
                            if (relTolerance > 0 && eNum != 0.0) details << "rel diff ${Math.abs((aNum - eNum) / eNum)}, tolerance ${relTolerance}"
                            errors << "Line ${i}, col ${j}: ${aNum} vs ${eNum} (${details.join('; ')})"
                        }
                        return
                    } catch (NumberFormatException e) {
                        // Fall through to string comparison
                    }
                }

                // String comparison
                if (val != eCols[j]) {
                    errors << "Line ${i}, col ${j}: '${val}' vs '${eCols[j]}'"
                }
            }
        }

        if (errors) {
            int shown = Math.min(errors.size(), maxErrors)
            String report = "TSV comparison failed with ${errors.size()} mismatches"
            if (errors.size() > maxErrors) {
                report += " (showing first ${maxErrors})"
            }
            report += ":\n" + errors.take(maxErrors).collect { "  - ${it}" }.join('\n')
            assert false : report
        }
    }

    /**
     * Compare text reports line-by-line, ignoring header/log lines.
     *
     * @param actual          Lines from the RustQC output
     * @param expected        Lines from the upstream reference
     * @param ignorePrefixes  Line prefixes to filter out (e.g. ['Load BAM', 'processing'])
     *
     * @throws AssertionError with line-by-line diff
     */
    static void textMatch(List<String> actual, List<String> expected,
                          List<String> ignorePrefixes = []) {
        actual   = prepareLines(actual, ignorePrefixes, true, true)
        expected = prepareLines(expected, ignorePrefixes, true, true)

        assert actual.size() == expected.size() :
            "Line count mismatch: actual=${actual.size()}, expected=${expected.size()}"

        List<String> errors = []
        actual.eachWithIndex { line, i ->
            if (line != expected[i]) {
                errors << "Line ${i}:\n    actual:   '${line}'\n    expected: '${expected[i]}'"
            }
        }

        if (errors) {
            int shown = Math.min(errors.size(), 20)
            assert false : "Text comparison failed with ${errors.size()} mismatches:\n" +
                errors.take(20).join('\n')
        }
    }

    /**
     * Compare two files line by line, exactly: no trimming, blank lines kept. Lines starting
     * with one of `skipPrefixes` are dropped from both sides first (e.g. version/command-line
     * headers). Use this, or md5, wherever the upstream bytes are reproduced.
     */
    static void exactMatch(List<String> actual, List<String> expected, List<String> skipPrefixes = []) {
        def keep = { List<String> lines -> lines.findAll { line -> !skipPrefixes.any { line.startsWith(it) } } }
        actual   = keep(actual)
        expected = keep(expected)
        if (actual == expected) return
        List<String> errors = []
        int n = Math.max(actual.size(), expected.size())
        for (int i = 0; i < n && errors.size() < 20; i++) {
            def a = i < actual.size() ? actual[i] : '<none>'
            def e = i < expected.size() ? expected[i] : '<none>'
            if (a != e) errors << "Line ${i}:\n    actual:   '${a}'\n    expected: '${e}'"
        }
        assert false : "Exact comparison failed (actual ${actual.size()} lines, expected ${expected.size()}):\n" +
            errors.join('\n')
    }

    /** Qualimap junction-motif rows: "    ACCT : 10.89%" in rnaseq_qc_results.txt, table rows in qualimapReport.html. */
    static final Map<String, java.util.regex.Pattern> QUALIMAP_MOTIF_ROWS = [
        'rnaseq_qc_results.txt': ~/^(\s+)([ACGTN]{4})( : )([\d.]+)(%)$/,
        'qualimapReport.html'  : ~/^(<td class="column1">)([ACGTN]{4})(<\/td><td class="column2">)([\d.]+)(%<\/td>)$/,
    ].asImmutable()

    /** Qualimap lists at most this many junction motifs. */
    static final int QUALIMAP_MOTIF_LIMIT = 11

    /**
     * The junction-motif rows of a Qualimap output (`file`: a key of QUALIMAP_MOTIF_ROWS) with
     * each run of equal percentages sorted by motif name, and the names of the last run masked
     * when the list is full (QUALIMAP_MOTIF_LIMIT rows: the cut may fall inside a tied run).
     * Percentages and the order of the runs are kept. Other lines are returned unchanged.
     *
     * Qualimap 2.3 orders tied motifs by reverse java.util.HashMap iteration order
     * (TranscriptDataHandler.computeSortedJunctionsMap, then the backwards top-11 loop in
     * RNASeqQCAnalysis), which depends on the table size and on the order the motifs were
     * first seen in its own read order. RustQC sorts ties by name instead, so only the set of
     * motifs in each tied run can be compared.
     */
    static List<String> qualimapMotifTiesAsSets(String file, List<String> lines) {
        java.util.regex.Pattern pattern = QUALIMAP_MOTIF_ROWS[file]
        if (pattern == null) return lines
        List<String> out = new ArrayList<String>(lines)
        List<Integer> rows = []
        List<java.util.regex.Matcher> found = []
        for (int i = 0; i < lines.size(); i++) {
            def m = pattern.matcher(lines[i])
            if (m.matches()) {
                rows << i
                found << m
            }
        }
        int start = 0
        while (start < rows.size()) {
            int end = start
            while (end + 1 < rows.size() && found[end + 1].group(4) == found[start].group(4)) end++
            boolean cutOff = rows.size() == QUALIMAP_MOTIF_LIMIT && end == rows.size() - 1
            List<String> names = (start..end).collect { found[it].group(2) }.sort()
            for (int k = start; k <= end; k++) {
                def m = found[k]
                String name = cutOff ? '<tied motif>' : names[k - start]
                out[rows[k]] = m.group(1) + name + m.group(3) + m.group(4) + m.group(5)
            }
            start = end + 1
        }
        return out
    }

    /**
     * Assert a file exists and has at least the given size in bytes.
     * Useful for checking plot files (PNG/PDF/SVG) without comparing content.
     */
    static void fileMinSize(java.nio.file.Path file, long minBytes) {
        assert java.nio.file.Files.exists(file) :
            "File not found: ${file}"
        long size = java.nio.file.Files.size(file)
        assert size >= minBytes :
            "File ${file.fileName} too small: ${size} bytes (minimum: ${minBytes})"
    }

    /**
     * Extract a range of lines from a file, useful for comparing
     * specific sections (e.g. data rows, skipping headers).
     */
    static List<String> extractLines(java.nio.file.Path file, int from, int to) {
        def lines = file.readLines()
        int end = Math.min(to, lines.size())
        return (from < end) ? lines.subList(from, end) : []
    }

    // ── Internal helpers ──────────────────────────────────────────────

    /**
     * Filter and clean lines: remove blank lines, trim whitespace,
     * remove lines matching any of the given prefixes.
     */
    private static List<String> prepareLines(List<String> lines,
                                              List<String> skipPrefixes,
                                              boolean trim,
                                              boolean skipBlank) {
        def result = lines
        if (trim) {
            result = result.collect { it.trim() }
        }
        if (skipBlank) {
            result = result.findAll { it != '' }
        }
        if (skipPrefixes) {
            result = result.findAll { line ->
                !skipPrefixes.any { prefix -> line.startsWith(prefix) }
            }
        }
        return result
    }
}
