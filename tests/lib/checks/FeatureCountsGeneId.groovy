package checks

/**
 * featureCounts -g gene_id (per-gene counts). Moved from the
 * "featurecounts - crosscheck per-gene counts vs upstream" test in
 * tests/rna/rustqc/featurecounts.nf.test, with the crosscheck as tightened in PR #52.
 */
class FeatureCountsGeneId extends ToolCheck {

    String getName() { 'featurecounts_geneid' }

    List<String> getOutputs() {
        ['featurecounts/{sample}.featureCounts.tsv', 'featurecounts/{sample}.featureCounts.tsv.summary']
    }

    Map<String, String> getReferences() {
        [
            'featurecounts_geneid/{sample}.featureCounts.tsv':         'featurecounts_geneid/{sample}.featureCounts.tsv',
            'featurecounts_geneid/{sample}.featureCounts.tsv.summary': 'featurecounts_geneid/{sample}.featureCounts.tsv.summary',
        ]
    }

    void crosscheck(Run run) {
        def actualLines   = run.lines(run.output(outputs[0]))
        def expectedLines = run.referenceLines('featurecounts_geneid/{sample}.featureCounts.tsv')

        // ── Per-gene counts ───────────────────────────────────────────
        // Identical to featureCounts -g gene_id line for line on every dataset: Geneid, the
        // per-exon Chr/Start/End/Strand lists (in Subread's order), Length and count. Only
        // the '# Program:' line (version and command line) is skipped.
        CompareUtils.exactMatch(actualLines, expectedLines, ['# Program:'])

        // ── Per-gene summary ──────────────────────────────────────────
        // Measured on hs_chr6_pe_rev: byte-identical to featureCounts -g gene_id (the
        // header echoes the BAM name, normalised by Run).
        run.assertIdentical(outputs[1], 'featurecounts_geneid/{sample}.featureCounts.tsv.summary',
            'featureCounts.tsv.summary')
    }

    // The summary in full; the ~3,000-line count table as an md5 of everything but the
    // '# Program:' line, which embeds the RustQC version and command line.
    Object regressionView(Run run) {
        def counts = run.lines(run.output(outputs[0])).findAll { !it.startsWith('#') }
        return [
            counts_md5: Run.md5(counts),
            genes:      counts.size() - 1,
            summary:    run.lines(run.output(outputs[1])),
        ]
    }
}
