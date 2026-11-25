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
        // dupMatrix: byte-identical to dupRadar (all 14 columns, counts and the derived
        // dupRate/RPK/RPKM columns in R's number formatting).
        run.assertIdentical(outputs[0], 'dupradar/dupMatrix.txt', 'dupMatrix.txt')

        // intercept/slope: byte-identical to dupRadar's intercept_slope.txt (R's cat(): 15
        // significant digits, a space after the intercept). The upstream references are made
        // with OpenBLAS pinned to its generic kernels (conf/modules.config), without which
        // their last digits depend on the CPU.
        //
        // Degenerate fit: with no read marked duplicate (every dataset except hs_chr6_pe_rev)
        // the duplication rate is 0 for every gene and the logistic fit has no finite
        // solution. R's glm() stops after 25 iterations at an intercept of ~3e-12 and a slope
        // of ~1, and those values are rounding noise accumulated over the iterations: R's
        // own result moves in the 12th-15th digit with the BLAS kernels. RustQC reproduces
        // glm.fit but not the BLAS summation order, so in that case only (upstream intercept
        // below 1e-9) the labels and layout must be identical and the values agree to 1e-10
        // relative (intercept) and 1e-12 relative (slope). Measured: at most 1.4e-12 and
        // 3.5e-14 (hs_ribo_chr19_22_se_fwd).
        def expectedLines = run.referenceLines('dupradar/intercept_slope.txt')
        def parse = { List<String> lines ->
            lines.findAll { it.trim() }.collectEntries { line ->
                def i = line.lastIndexOf(':')
                [(line.substring(0, i)): line.substring(i + 1).trim() as Double]
            }
        }
        def expected = parse(expectedLines)
        boolean degenerate = expected.values().any { Math.abs(it) < 1e-9 }
        if (!degenerate) {
            run.assertIdentical(outputs[1], 'dupradar/intercept_slope.txt', 'intercept_slope.txt')
            return
        }
        def actualLines = run.lines(run.output(outputs[1]))
        def layout = { List<String> lines -> lines.collect { it.replaceAll(/: \S+/, ': <value>') } }
        CompareUtils.exactMatch(layout(actualLines), layout(expectedLines))
        def actual = parse(actualLines)
        expected.each { label, value ->
            double tol = Math.abs(value) < 1e-9 ? 1e-10 : 1e-12
            double relDiff = Math.abs(actual[label] - value) / Math.abs(value)
            assert relDiff <= tol : \
                "${label}: RustQC=${actual[label]} upstream=${value} (relative diff ${relDiff}, degenerate fit, allowed ${tol})"
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
