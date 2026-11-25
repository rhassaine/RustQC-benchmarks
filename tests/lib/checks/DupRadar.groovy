package checks

/** dupRadar. Moved from tests/rna/rustqc/dupradar.nf.test (crosscheck as tightened in PR #52). */
class DupRadar extends ToolCheck {

    String getName() { 'dupradar' }

    List<String> getOutputs() { ['dupradar/{sample}_dupMatrix.txt', 'dupradar/{sample}_intercept_slope.txt'] }

    Map<String, String> getReferences() {
        [
            'dupradar/dupMatrix.txt':       'dupradar/gene_data/{sample}_dupMatrix.txt',
            'dupradar/intercept_slope.txt': 'dupradar/intercepts_slope/{sample}_intercept_slope.txt',
        ]
    }

    void crosscheck(Run run) {
        // Compare the original bytes, including R's numeric formatting and trailing space.
        def names = ['dupMatrix.txt', 'intercept_slope.txt']
        names.eachWithIndex { name, i ->
            def actual = run.output(outputs[i])
            def expected = run.reference("dupradar/${name}")
            assert actual.isFile() : "Missing RustQC output: ${actual}"
            assert expected.isFile() : "Missing upstream reference: ${expected}"
            assert Run.md5(actual) == Run.md5(expected) : "${name} differs from upstream dupRadar"
        }
    }

    // dupMatrix as md5 + row count; intercept/slope in full.
    Object regressionView(Run run) {
        def matrix = run.lines(run.output(outputs[0]))
        return [
            matrix_md5: Run.md5(matrix),
            genes:      matrix.size() - 1,
            slope:      run.lines(run.output(outputs[1])),
        ]
    }
}
