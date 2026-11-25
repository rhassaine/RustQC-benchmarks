package checks

/** RSeQC read_distribution.py. Moved from tests/rna/rustqc/read_distribution.nf.test (crosscheck as tightened in PR #52). */
class ReadDistribution extends ToolCheck {

    String getName() { 'read_distribution' }

    List<String> getOutputs() { ['rseqc/read_distribution/{sample}.read_distribution.txt'] }

    Map<String, String> getReferences() {
        ['rseqc/read_distribution/read_distribution.txt': 'rseqc/read_distribution/{sample}.read_distribution.txt']
    }

    // Byte-identical to RSeQC read_distribution.py (run on the GTF2BED-derived BED, as the
    // pipeline does), trailing spaces of its table rows included.
    void crosscheck(Run run) {
        run.assertIdentical(outputs[0], 'rseqc/read_distribution/read_distribution.txt', 'read_distribution.txt')
    }

    Object regressionView(Run run) {
        return run.lines(run.output(outputs[0])).findAll { it.trim() }.collect { it.trim() }
    }
}
